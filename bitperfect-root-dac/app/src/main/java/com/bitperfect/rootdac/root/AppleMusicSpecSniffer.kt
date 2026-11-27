package com.bitperfect.rootdac.root

import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DetectedAudioSpec(
    val sampleRate: Int,
    val bitDepth: Int = 24, // Fixed 24-bit (S24_3LE, Alt#2): 16-bit lossless is bit-identical zero-padded
    val rawFormatName: String = "FormatInfo(24-bit S24_3LE)",
    val detectionSource: String = "dumpsys audio",
    val piid: Long = 0L
) {
    fun sameAudioParams(other: DetectedAudioSpec?): Boolean {
        return other != null && sampleRate == other.sampleRate && bitDepth == other.bitDepth
    }
}

data class RemoteSubmixProfile(
    val supportedRates: Set<Int> = setOf(44100, 48000),
    val maxRate: Int = 48000,
    val formatSummary: String = "PCM_16_BIT"
) {
    /**
     * Resolves the highest-fidelity native r_submix sample rate for a given Apple Music source rate.
     * - If r_submix natively supports sourceRate (e.g. 44.1k/48k, or 96k/192k on a Hi-Res modded ROM),
     *   returns sourceRate directly (100% Zero-SRC!).
     * - If sourceRate exceeds r_submix's native ceiling (e.g. 192k on stock 48k-capped r_submix),
     *   maps by EXACT integer divisor (192k/96k -> 48k, 176.4k/88.2k -> 44.1k) so AudioFlinger
     *   RecordThread NEVER performs blurry 4x low-quality upsampling!
     */
    fun resolveCaptureSampleRate(sourceRate: Int): Pair<Int, Boolean> {
        if (sourceRate in supportedRates) {
            return sourceRate to true
        }
        val is441Family = (sourceRate % 44100 == 0)
        val sameFamilyHighest = supportedRates
            .filter { r -> if (is441Family) (r % 44100 == 0) else (r % 48000 == 0) }
            .filter { it <= sourceRate }
            .maxOrNull()
        if (sameFamilyHighest != null) {
            return sameFamilyHighest to false
        }
        val highestUnder = supportedRates.filter { it <= sourceRate }.maxOrNull()
        return (highestUnder ?: maxRate) to false
    }
}

data class SnifferStatus(
    val running: Boolean = false,
    val appleMusicUid: Int = -1,
    val appleMusicPids: List<Int> = emptyList(),
    val isPlaying: Boolean = false,
    val currentSpec: DetectedAudioSpec? = null,
    val isoSchedFifoActive: Boolean = false,
    val isoSchedTid: Int = 0
)

/**
 * Module 2 (Android 17 Pure-AIDL Optimized):
 *
 * Lightweight persistent libsu Root daemon polling ONLY `dumpsys audio` every 600ms
 * (never locks audioserver with `dumpsys media.audio_flinger`, and skips immediately
 * when `pidof com.apple.android.music` is empty).
 *
 * Extracts the true native source sample rate directly from Android 17's
 * `AudioPlaybackConfiguration` line:
 *   ... u/pid:<uid>/<pid> state:started ... FormatInfo{..., sampleRate=192000}
 *
 * Also handles kernel SCHED_FIFO real-time priority elevation via `chrt -f -p <prio> <tid>`.
 */
class AppleMusicSpecSniffer(
    context: Context,
    private val onLog: (level: Int, msg: String) -> Unit
) {
    companion object {
        const val APPLE_MUSIC_PKG = "com.apple.android.music"
        private const val POLL_INTERVAL_MS = 800L
        private const val FIXED_TARGET_BIT_DEPTH = 24 // Default Alt#2 S24_3LE (UI can toggle to 32-bit S32_LE)

        private val FORMAT_INFO_SRATE_REGEX = Regex("""FormatInfo\{[^}]*sampleRate=(\d+)""")
        private val PIID_REGEX = Regex("""piid:(\d+)""")
        private val VALID_RATES = setOf(
            44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
        )
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _status = MutableStateFlow(SnifferStatus())
    val status: StateFlow<SnifferStatus> = _status.asStateFlow()

    fun resolveAppleMusicUid(): Int {
        try {
            val info = appContext.packageManager.getApplicationInfo(APPLE_MUSIC_PKG, 0)
            if (info.uid > 0) return info.uid
        } catch (_: PackageManager.NameNotFoundException) {
            // Fall through to Root probe
        } catch (_: Exception) {
            // Fall through
        }

        val res = Shell.cmd(
            "stat -c %u /data/data/$APPLE_MUSIC_PKG 2>/dev/null || " +
                "dumpsys package $APPLE_MUSIC_PKG | grep -m1 -E 'userId=|appId=' | sed 's/[^0-9]//g'"
        ).exec()
        val uidStr = res.out.firstOrNull { it.trim().all(Char::isDigit) && it.trim().isNotEmpty() }?.trim()
        return uidStr?.toIntOrNull() ?: -1
    }

    /**
     * One-shot Root Doze & AppOps background exemption for our package and Apple Music.
     */
    fun applyRootBackgroundKeepAlive() {
        scope.launch {
            val pkg = appContext.packageName
            val amPkg = APPLE_MUSIC_PKG
            val cmd = """
                for P in $pkg $amPkg; do
                    dumpsys deviceidle whitelist +${'$'}P >/dev/null 2>&1 || true
                    cmd appops set ${'$'}P RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
                    cmd appops set ${'$'}P RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
                    am set-standby-bucket ${'$'}P active >/dev/null 2>&1 || true
                done
            """.trimIndent()
            val res = Shell.cmd(cmd).exec()
            if (res.isSuccess) {
                onLog(1, "✓ 已同步配置后台常驻白名单 ($pkg, $amPkg)")
            }
        }
    }

    /**
     * Queries `dumpsys media.audio_policy` once to inspect the true native sample rates & format
     * supported by this ROM's `r_submix` HAL (e.g. 8k..48k PCM_16_BIT on stock Android 17).
     */
    fun probeRemoteSubmixProfile(): RemoteSubmixProfile {
        val cmd = "dumpsys media.audio_policy | grep -iA 10 '\"r_submix input\"'"
        val res = Shell.cmd(cmd).exec()
        if (!res.isSuccess || res.out.isEmpty()) {
            return RemoteSubmixProfile()
        }

        val rates = linkedSetOf<Int>()
        val formats = linkedSetOf<String>()
        val numRegex = Regex("""\b(\d{4,6})\b""")
        val fmtRegex = Regex("""AUDIO_FORMAT_[A-Z0-9_]+""")

        for (line in res.out) {
            if (line.contains("sampling rates:", ignoreCase = true)) {
                numRegex.findAll(line).forEach { m ->
                    val r = m.groupValues[1].toIntOrNull()
                    if (r != null && r in 8000..768000) {
                        rates.add(r)
                    }
                }
            }
            fmtRegex.findAll(line).forEach { m ->
                formats.add(m.value.removePrefix("AUDIO_FORMAT_"))
            }
        }

        if (rates.isEmpty()) {
            return RemoteSubmixProfile()
        }
        val profile = RemoteSubmixProfile(
            supportedRates = rates,
            maxRate = rates.maxOrNull() ?: 48000,
            formatSummary = if (formats.isEmpty()) "PCM_16_BIT" else formats.joinToString("/")
        )
        onLog(
            1,
            "检测到系统 r_submix HAL 原生能力: 格式=${profile.formatSummary}, 采样率=${profile.supportedRates.sorted()} Hz " +
                "(上限 ${profile.maxRate} Hz)"
        )
        return profile
    }

    /**
     * Uses persistent libsu Root shell to elevate a native/audio thread TID to kernel SCHED_FIFO.
     * IMPORTANT: Android Toybox `chrt` uses `-p PID PRIORITY` (PID first, priority second!),
     * unlike GNU util-linux `chrt -p PRIORITY PID`. Try Toybox syntax first, fallback to GNU.
     */
    fun elevateThreadToSchedFifo(tid: Int, priority: Int, label: String, isIsoThread: Boolean = false) {
        if (tid <= 0) return
        scope.launch {
            val cmd = "(chrt -f -p $tid $priority 2>/dev/null || " +
                "toybox chrt -f -p $tid $priority 2>/dev/null || " +
                "chrt -f -p $priority $tid 2>&1) && " +
                "(chrt -p $tid 2>&1 || toybox chrt -p $tid 2>&1)"
            val res = Shell.cmd(cmd).exec()
            val outJoined = (res.out + res.err).joinToString(" | ").trim()
            val fifoConfirmed = outJoined.contains("SCHED_FIFO", ignoreCase = true)
            if (fifoConfirmed) {
                onLog(1, "✓ Root SCHED_FIFO 已生效 [$label tid=$tid, prio=$priority]: $outJoined")
            } else {
                onLog(2, "Root chrt 提权未确认 [$label tid=$tid]: $outJoined")
            }
            if (isIsoThread) {
                _status.value = _status.value.copy(
                    isoSchedFifoActive = fifoConfirmed,
                    isoSchedTid = tid
                )
            }
        }
    }

    fun clearIsoSchedStatus() {
        _status.value = _status.value.copy(isoSchedFifoActive = false, isoSchedTid = 0)
    }

    fun start(onStableSpecChanged: (DetectedAudioSpec) -> Unit) {
        stop()

        pollJob = scope.launch {
            val uid = resolveAppleMusicUid()
            if (uid <= 0) {
                onLog(3, "无法解析 $APPLE_MUSIC_PKG 的 UID，请确认已安装 Apple Music 并授予 Root 权限")
                _status.value = SnifferStatus(running = false, appleMusicUid = -1)
                return@launch
            }
            onLog(
                1,
                "Root 轻量嗅探守护进程已启动 (仅 dumpsys audio, Apple Music UID=$uid, 周期=${POLL_INTERVAL_MS}ms, 固定 24-bit S24_3LE)"
            )
            _status.value = _status.value.copy(running = true, appleMusicUid = uid)

            var pendingSpec: DetectedAudioSpec? = null
            var stableCount = 0
            var lastEmittedSpec: DetectedAudioSpec? = null
            var lastPlayingState = false
            var warnedMutedVolume = false

            while (isActive) {
                try {
                    val probe = probeDumpsysAudioOnce(uid)
                    if (probe.isPlaying != lastPlayingState) {
                        lastPlayingState = probe.isPlaying
                        onLog(
                            1,
                            if (probe.isPlaying) "检测到 Apple Music 处于活跃播放状态 (PIDs=${probe.pids})"
                            else "Apple Music 当前处于暂停/待机状态"
                        )
                    }

                    if (probe.streamVolumeMuted && !warnedMutedVolume) {
                        warnedMutedVolume = true
                        onLog(
                            2,
                            "注意: dumpsys audio 显示 Apple Music 处于 mutedState:streamVolume（手机系统媒体音量为静音）。" +
                                "请将手机系统媒体音量调大（推荐满格），再通过下方 DAC 硬件音量滑块调节耳机响度！"
                        )
                    } else if (!probe.streamVolumeMuted) {
                        warnedMutedVolume = false
                    }

                    val detected = probe.spec
                    if (detected != null) {
                        if (detected.sameAudioParams(pendingSpec)) {
                            stableCount++
                        } else {
                            pendingSpec = detected
                            stableCount = 1
                        }

                        // Lock immediately on first detection; require 1-cycle debounce (2 consecutive ticks)
                        // on subsequent track switches to avoid transient crossfade bounce.
                        val readyToEmit = (lastEmittedSpec == null) || (stableCount >= 2)
                        if (readyToEmit && !detected.sameAudioParams(lastEmittedSpec)) {
                            lastEmittedSpec = detected
                            onLog(
                                1,
                                "★ 嗅探到 Apple Music 原始采样率: ${detected.sampleRate} Hz " +
                                    "(piid=${detected.piid}) -> 目标固定 24-bit (S24_3LE)"
                            )
                            _status.value = _status.value.copy(
                                running = true,
                                appleMusicUid = uid,
                                appleMusicPids = probe.pids,
                                isPlaying = true,
                                currentSpec = detected
                            )
                            onStableSpecChanged(detected)
                        } else {
                            _status.value = _status.value.copy(
                                running = true,
                                appleMusicUid = uid,
                                appleMusicPids = probe.pids,
                                isPlaying = probe.isPlaying
                            )
                        }
                    } else {
                        _status.value = _status.value.copy(
                            running = true,
                            appleMusicUid = uid,
                            appleMusicPids = probe.pids,
                            isPlaying = probe.isPlaying
                        )
                    }
                } catch (e: Exception) {
                    onLog(2, "Root dumpsys audio 轮询异常: ${e.message}")
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _status.value = _status.value.copy(running = false)
    }

    /**
     * Lightweight diagnostic snapshot showing `pidof` and filtered `dumpsys audio` lines
     * for Apple Music UID, returning full text for 1-tap clipboard copy.
     */
    fun dumpDiagnosticSnapshot(headerLine: String = "", onReady: ((String) -> Unit)? = null) {
        scope.launch {
            val uid = resolveAppleMusicUid()
            val cmd = """
                echo "=== Apple Music UID=$uid ==="
                AM_PIDS=${'$'}(pidof $APPLE_MUSIC_PKG)
                echo "PIDs: ${'$'}AM_PIDS"
                for P in ${'$'}AM_PIDS; do
                    echo "  PID ${'$'}P: oom_score_adj=${'$'}(cat /proc/${'$'}P/oom_score_adj 2>/dev/null) freeze=${'$'}(cat /sys/fs/cgroup/uid_$uid/pid_${'$'}P/cgroup.freeze 2>/dev/null)"
                done
                echo "--- dumpsys audio (u/pid:$uid/) ---"
                dumpsys audio | grep "u/pid:$uid/"
                echo "--- dumpsys media.audio_flinger (threads/submix/underrun) ---"
                dumpsys media.audio_flinger | grep -iE "Output thread|Input thread|Duplicating|submix|Sample rate|HAL frame|Frame count|Standby|underrun|Active tracks|$uid" | head -n 45
                echo "--- recent audioserver / LSPosed / USB logcat ---"
                logcat -d -t 300 | grep -iE "BP_LspHook|submix|audioflinger|audiotrack|underrun|overrun|AudioPolicy" | tail -n 25
            """.trimIndent()
            val res = Shell.cmd(cmd).exec()
            val sb = StringBuilder(4096)
            sb.append("========== BitPerfect Root DAC 诊断快照 ==========\n")
            if (headerLine.isNotBlank()) {
                sb.append(headerLine).append('\n')
            }
            onLog(1, "========== Root 嗅探与 audioserver 诊断快照 ==========")
            res.out.forEach { line ->
                sb.append(line).append('\n')
                onLog(0, line)
            }
            if (res.err.isNotEmpty()) {
                res.err.forEach { errLine ->
                    sb.append("stderr: ").append(errLine).append('\n')
                    onLog(2, "stderr: $errLine")
                }
            }
            sb.append("====================================================")
            onLog(1, "====================================================")
            onReady?.invoke(sb.toString())
        }
    }

    private data class ProbeResult(
        val pids: List<Int>,
        val isPlaying: Boolean,
        val spec: DetectedAudioSpec?,
        val streamVolumeMuted: Boolean = false
    )

    private fun probeDumpsysAudioOnce(appleMusicUid: Int): ProbeResult {
        // Ultra-lightweight single command: skips dumpsys audio entirely if Apple Music isn't running,
        // and filters in-shell to only Apple Music UID lines (~1-3 lines returned!).
        val cmd = """
            PIDS=${'$'}(pidof $APPLE_MUSIC_PKG)
            if [ -z "${'$'}PIDS" ]; then echo "__NO_PID__"; exit 0; fi
            echo "__PIDS__:${'$'}PIDS"
            dumpsys audio | grep "u/pid:$appleMusicUid/"
        """.trimIndent()

        val result = Shell.cmd(cmd).exec()
        if (!result.isSuccess || result.out.isEmpty()) {
            return ProbeResult(emptyList(), false, null)
        }

        val firstLine = result.out.first().trim()
        if (firstLine == "__NO_PID__") {
            return ProbeResult(emptyList(), false, null)
        }

        val pids = if (firstLine.startsWith("__PIDS__:")) {
            firstLine.removePrefix("__PIDS__:").trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
        } else {
            emptyList()
        }

        val uidNeedle = "u/pid:$appleMusicUid/"
        var bestSpec: DetectedAudioSpec? = null
        var anyStarted = false
        var streamVolMuted = false

        for (rawLine in result.out) {
            if (!rawLine.contains(uidNeedle)) continue
            if (!rawLine.contains("state:started")) continue

            anyStarted = true
            if (rawLine.contains("mutedState:streamVolume")) {
                streamVolMuted = true
            }

            val rateMatch = FORMAT_INFO_SRATE_REGEX.find(rawLine) ?: continue
            val sampleRate = rateMatch.groupValues[1].toIntOrNull() ?: continue
            if (sampleRate !in VALID_RATES) continue

            val piid = PIID_REGEX.find(rawLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val candidate = DetectedAudioSpec(
                sampleRate = sampleRate,
                bitDepth = FIXED_TARGET_BIT_DEPTH,
                rawFormatName = "FormatInfo(sampleRate=$sampleRate)",
                detectionSource = "dumpsys audio",
                piid = piid
            )

            // Prefer the newest active AudioTrack (highest piid) when tracks overlap during crossfade
            if (bestSpec == null || candidate.piid >= bestSpec.piid) {
                bestSpec = candidate
            }
        }

        return ProbeResult(
            pids = pids,
            isPlaying = anyStarted,
            spec = bestSpec,
            streamVolumeMuted = streamVolMuted
        )
    }
}
