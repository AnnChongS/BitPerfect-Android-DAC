package com.bitperfect.usbbridge.shizuku

import android.content.Context
import android.content.pm.PackageManager
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
    val bitDepth: Int,
    val sourceTag: String,
    val piid: Int = 0,
    val detectedAtMs: Long = System.currentTimeMillis()
)

data class RemoteSubmixProfile(
    val supportedRates: List<Int>,
    val maxSupportedRate: Int,
    val supportsFloat: Boolean,
    val rawSummary: String
)

data class SnifferStatus(
    val running: Boolean = false,
    val appleMusicUid: Int = -1,
    val appleMusicRunning: Boolean = false,
    val lastDetectedSpec: DetectedAudioSpec? = null,
    val note: String = "未启动"
)

/**
 * Pure Shizuku (uid=2000, ZERO Root) Apple Music sample-rate sniffer & r_submix capability probe.
 *
 * Uses Shizuku UserService `dumpsys audio` polling every 800ms to extract Apple Music's
 * native sample rate from Android 14-17's `AudioPlaybackConfiguration ... FormatInfo{..., sampleRate=...}`.
 */
class ShizukuSpecSniffer(
    context: Context,
    private val shizukuController: ShizukuController,
    private val onLog: (level: Int, msg: String) -> Unit
) {
    companion object {
        const val APPLE_MUSIC_PKG = "com.apple.android.music"
        private const val POLL_INTERVAL_MS = 800L

        private val VALID_RATES = setOf(
            44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
        )

        // Android 14-17 dumpsys audio exact format:
        // AudioPlaybackConfiguration piid:611855 ... u/pid:10362/24843 state:started ... FormatInfo{isSpatialized=false, channelMask=0x3, sampleRate=192000}
        private val FORMAT_INFO_RATE_REGEX = Regex("""FormatInfo\{[^}]*sampleRate=(\d+)""")
        private val PIID_REGEX = Regex("""piid:(\d+)""")
        private val PID_FROM_UPID_REGEX = Regex("""u/pid:\d+/(\d+)""")
        private val SAMPLING_RATES_LINE_REGEX = Regex("""sampling rates:\s*([0-9,\s]+)""")
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(SnifferStatus())
    val status: StateFlow<SnifferStatus> = _status.asStateFlow()

    @Volatile
    private var pollJob: Job? = null

    @Volatile
    private var lastCommittedSampleRate: Int = -1

    @Volatile
    private var lastActiveTrackPiid: Int = -1

    /**
     * Resolves Apple Music UID via standard Android PackageManager (works with ZERO Root & ZERO Shizuku!).
     */
    fun resolveAppleMusicUid(): Int {
        return try {
            val info = appContext.packageManager.getApplicationInfo(
                APPLE_MUSIC_PKG,
                PackageManager.ApplicationInfoFlags.of(0)
            )
            info.uid
        } catch (_: Exception) {
            -1
        }
    }

    fun start(
        appleMusicUid: Int,
        onSpecChanged: (DetectedAudioSpec) -> Unit
    ) {
        stop()
        lastCommittedSampleRate = -1
        lastActiveTrackPiid = -1

        _status.value = SnifferStatus(
            running = true,
            appleMusicUid = appleMusicUid,
            note = "Shizuku 监听 Apple Music (UID=$appleMusicUid, dumpsys audio)"
        )
        onLog(
            1,
            "Shizuku 自动采样率嗅探已启动 (dumpsys audio, UID=$appleMusicUid, 周期=${POLL_INTERVAL_MS}ms, 零 Root)"
        )

        pollJob = scope.launch {
            var lastLoggedPlaying = false
            while (isActive) {
                try {
                    if (!shizukuController.state.value.isReady) {
                        _status.value = _status.value.copy(
                            note = "等待 Shizuku UserService 就绪..."
                        )
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    val output = shizukuController.dumpAudioPlayback(appleMusicUid)
                    if (output != null) {
                        val activeTrack = parseActiveAppleMusicPlayback(output, appleMusicUid)
                        if (activeTrack == null) {
                            if (lastLoggedPlaying) {
                                onLog(1, "Apple Music 当前未处于播放态 (waiting for state:started)")
                                lastLoggedPlaying = false
                            }
                            _status.value = _status.value.copy(
                                appleMusicRunning = false,
                                note = "等待 Apple Music 播放..."
                            )
                        } else {
                            if (!lastLoggedPlaying) {
                                onLog(
                                    1,
                                    "检测到 Apple Music 活跃播放 (PID=${activeTrack.pid}, piid=${activeTrack.piid})"
                                )
                                lastLoggedPlaying = true
                            }

                            val detectedRate = activeTrack.sampleRate
                            val spec = DetectedAudioSpec(
                                sampleRate = detectedRate,
                                bitDepth = 24,
                                sourceTag = "shizuku:dumpsys audio (piid=${activeTrack.piid})",
                                piid = activeTrack.piid
                            )

                            val rateChanged = (detectedRate != lastCommittedSampleRate)
                            val piidChanged = (activeTrack.piid != lastActiveTrackPiid)
                            lastActiveTrackPiid = activeTrack.piid

                            _status.value = SnifferStatus(
                                running = true,
                                appleMusicUid = appleMusicUid,
                                appleMusicRunning = true,
                                lastDetectedSpec = spec,
                                note = "Shizuku 实时锁定: ${detectedRate} Hz (piid=${activeTrack.piid})"
                            )

                            if (rateChanged) {
                                lastCommittedSampleRate = detectedRate
                                onLog(
                                    1,
                                    "★ Shizuku 嗅探到 Apple Music 原始采样率: $detectedRate Hz (piid=${activeTrack.piid})"
                                )
                                onSpecChanged(spec)
                            } else if (piidChanged && detectedRate > 48000) {
                                // Hi-Res track switch (e.g. 192k -> 192k new AudioTrack piid):
                                // Android / Qualcomm PAL renegotiates Hi-Res USB stream; trigger clean rebind!
                                onLog(
                                    1,
                                    "★ Shizuku 检测到 Hi-Res 新音轨 ($detectedRate Hz, piid=${activeTrack.piid}) -> 触发 PAL 防卡顿重绑定"
                                )
                                onSpecChanged(spec)
                            } else if (piidChanged) {
                                onLog(
                                    0,
                                    "同标准采样率切歌: $detectedRate Hz (piid=${activeTrack.piid}), 保持硬件无缝输出"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    onLog(2, "Shizuku 采样率轮询异常: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        lastCommittedSampleRate = -1
        lastActiveTrackPiid = -1
        _status.value = _status.value.copy(
            running = false,
            note = "已停止"
        )
    }

    fun probeRemoteSubmixProfile(): RemoteSubmixProfile {
        val raw = shizukuController.dumpAudioPolicySubmix()
        if (raw.isNullOrBlank()) {
            return RemoteSubmixProfile(
                supportedRates = listOf(44100, 48000),
                maxSupportedRate = 48000,
                supportsFloat = false,
                rawSummary = "默认安全档位 [44100, 48000] Hz (r_submix 保守上限)"
            )
        }

        val rates = linkedSetOf<Int>()
        val supportsFloat = raw.contains("AUDIO_FORMAT_PCM_FLOAT", ignoreCase = true)
        for (line in raw.lineSequence()) {
            val match = SAMPLING_RATES_LINE_REGEX.find(line) ?: continue
            match.groupValues[1].split(",").forEach { token ->
                val hz = token.trim().toIntOrNull()
                if (hz != null && hz in VALID_RATES) {
                    rates.add(hz)
                }
            }
        }

        val sorted = if (rates.isNotEmpty()) rates.sorted() else listOf(44100, 48000)
        val maxRate = sorted.maxOrNull() ?: 48000
        val summary = "r_submix HAL 原生支持: $sorted Hz (最高 $maxRate Hz, float=${if (supportsFloat) "Y" else "N"})"
        onLog(1, "Shizuku 探测到系统 $summary")
        return RemoteSubmixProfile(
            supportedRates = sorted,
            maxSupportedRate = maxRate,
            supportsFloat = supportsFloat,
            rawSummary = summary
        )
    }

    private data class ActivePlaybackInfo(
        val piid: Int,
        val pid: Int,
        val sampleRate: Int
    )

    private fun parseActiveAppleMusicPlayback(output: String, appleMusicUid: Int): ActivePlaybackInfo? {
        val uidNeedle = "u/pid:$appleMusicUid/"
        var best: ActivePlaybackInfo? = null

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (!line.contains(uidNeedle)) continue
            if (!line.contains("state:started")) continue

            val rateMatch = FORMAT_INFO_RATE_REGEX.find(line) ?: continue
            val rate = rateMatch.groupValues[1].toIntOrNull() ?: continue
            if (rate !in VALID_RATES) continue

            val piid = PIID_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val pid = PID_FROM_UPID_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            if (best == null || piid >= best.piid) {
                best = ActivePlaybackInfo(piid = piid, pid = pid, sampleRate = rate)
            }
        }
        return best
    }
}
