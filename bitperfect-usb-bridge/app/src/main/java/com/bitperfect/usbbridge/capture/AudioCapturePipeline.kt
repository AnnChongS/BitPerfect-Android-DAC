package com.bitperfect.usbbridge.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import com.bitperfect.usbbridge.nativebridge.NativeUsbAudioBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

data class ActiveCaptureConfig(
    val sampleRate: Int,
    val channels: Int = 2,
    val recordBufferBytes: Int = 0,
    val monoPipePreFillDelayMs: Int = 40,
    val lowLatencyMode: Boolean = false,
    val targetUid: Int? = null
) {
    val frameBytes: Int
        get() = channels * 4 // ENCODING_PCM_FLOAT = 4 bytes/sample * 2ch = 8 bytes/frame
}

/**
 * Universal MediaProjection AudioPlaybackCapture pipeline:
 *  - Supports BOTH Global Media/Game Tracking (excludeUid(myUid)) and Single-App Lock (addMatchingUid)
 *  - Captures USAGE_MEDIA + USAGE_GAME (rhythm games) + USAGE_UNKNOWN (non-mainstream audio apps)
 *  - High-precision 32-bit IEEE Float (ENCODING_PCM_FLOAT, 8B/frame) matching AudioFlinger internal mixer
 *  - Adaptive Hi-Fi (200ms buffer / 40ms pre-fill / 20ms chunks) & Rhythm-Game Low-Latency (60ms / 20ms / 10ms)
 */
class AudioCapturePipeline(
    private val onLog: (level: Int, msg: String) -> Unit
) {
    companion object {
        private const val HIFI_MONO_PIPE_PREFILL_MS = 40
        private const val LOW_LATENCY_MONO_PIPE_PREFILL_MS = 20
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    val isRunning: Boolean
        get() = running.get()

    @SuppressLint("MissingPermission")
    fun startCapture(
        mediaProjection: MediaProjection,
        targetUid: Int?,
        sampleRate: Int,
        lowLatencyMode: Boolean = false,
        onFatalError: (String) -> Unit
    ): Result<ActiveCaptureConfig> {
        stopCapture()

        val captureBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        if (targetUid != null && targetUid > 0) {
            captureBuilder.addMatchingUid(targetUid)
        } else {
            // Global Media & Game Mode: capture any third-party app while excluding ourselves to prevent feedback loop
            captureBuilder.excludeUid(Process.myUid())
        }
        val captureConfig = captureBuilder.build()

        val channels = 2
        val frameBytes = channels * 4 // PCM_FLOAT stereo = 8 bytes/frame

        var createdRecord: AudioRecord? = null
        var chosenCfg: ActiveCaptureConfig? = null
        var lastError = ""

        try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            if (minBuf <= 0) {
                lastError = "getMinBufferSize(${sampleRate}Hz, PCM_FLOAT)=$minBuf"
            } else {
                val targetBufMs = if (lowLatencyMode) 60 else 200
                val targetBytes = ((sampleRate * targetBufMs) / 1000) * frameBytes
                val minMultiplier = if (lowLatencyMode) 2 else 4
                val bufferBytes = maxOf(minBuf * minMultiplier, targetBytes)
                val preFillDelayMs = if (lowLatencyMode) {
                    LOW_LATENCY_MONO_PIPE_PREFILL_MS
                } else {
                    HIFI_MONO_PIPE_PREFILL_MS
                }

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                val rec = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()

                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    createdRecord = rec
                    chosenCfg = ActiveCaptureConfig(
                        sampleRate = rec.sampleRate,
                        channels = channels,
                        recordBufferBytes = bufferBytes,
                        monoPipePreFillDelayMs = preFillDelayMs,
                        lowLatencyMode = lowLatencyMode,
                        targetUid = targetUid
                    )
                } else {
                    lastError = "AudioRecord.STATE_INITIALIZED failed for ${sampleRate}Hz PCM_FLOAT"
                    rec.release()
                }
            }
        } catch (e: Exception) {
            lastError = "PCM_FLOAT@${sampleRate}Hz exception: ${e.message}"
        }

        if (createdRecord == null || chosenCfg == null) {
            val msg = "无法初始化 PCM_FLOAT AudioRecord @ ${sampleRate}Hz ($lastError)"
            onLog(3, msg)
            return Result.failure(IllegalStateException(msg))
        }

        val finalRecord = createdRecord
        val finalCfg = chosenCfg
        audioRecord = finalRecord
        running.set(true)

        val scopeDesc = if (targetUid != null && targetUid > 0) {
            "单应用锁定 UID=$targetUid"
        } else {
            "全局媒体/音游追踪 (excludeSelf=${Process.myUid()})"
        }
        val latencyTag = if (lowLatencyMode) "低延迟音游 ~60ms" else "Hi-Fi 抗抖动 ~200ms"
        onLog(
            1,
            "AudioPlaybackCapture 已初始化: $scopeDesc | ${finalCfg.sampleRate} Hz | " +
                "PCM_FLOAT (USAGE_MEDIA+GAME+UNKNOWN) | Buf=${finalCfg.recordBufferBytes}B ($latencyTag) | " +
                "MonoPipe预蓄水=${finalCfg.monoPipePreFillDelayMs}ms"
        )

        val thread = Thread(
            {
                captureLoop(finalRecord, finalCfg, onFatalError)
            },
            "BP_AudioCapture"
        )
        captureThread = thread
        thread.start()

        return Result.success(finalCfg)
    }

    fun stopCapture() {
        running.set(false)

        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            }
        }

        val thread = captureThread
        captureThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(1200) }
        }

        if (rec != null) {
            runCatching { rec.release() }
        }
    }

    private fun captureLoop(
        record: AudioRecord,
        cfg: ActiveCaptureConfig,
        onFatalError: (String) -> Unit
    ) {
        val tid = Process.myTid()
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
            // Standard non-root urgent audio priority
        }

        val frameBytes = cfg.frameBytes
        // 10ms read chunks in low-latency rhythm-game mode, 20ms in standard Hi-Fi mode
        val chunkDivisor = if (cfg.lowLatencyMode) 100 else 50
        val chunkMsLabel = if (cfg.lowLatencyMode) 10 else 20
        val chunkFrames = maxOf(128, cfg.sampleRate / chunkDivisor)
        val chunkBytes = chunkFrames * frameBytes
        val directBuf = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder())

        var totalReads = 0L
        var misalignedReads = 0L
        var maxReadGapMs = 0L
        var lastReadEndNs = 0L

        val healthIntervalReads = if (cfg.lowLatencyMode) 500L else 250L
        val silenceBlocksThreshold = if (cfg.lowLatencyMode) 360 else 180 // ~3.6s

        try {
            record.startRecording()

            if (cfg.monoPipePreFillDelayMs > 0 && running.get()) {
                var remaining = cfg.monoPipePreFillDelayMs
                while (remaining > 0 && running.get()) {
                    val step = minOf(10, remaining)
                    Thread.sleep(step.toLong())
                    remaining -= step
                }
            }

            onLog(
                1,
                "AudioRecord 捕获线程已启动 (tid=$tid, chunk=$chunkFrames frames / ${chunkBytes}B = ${chunkMsLabel}ms, PCM_FLOAT)"
            )

            var consecutiveZeroBlocks = 0
            var warnedSilence = false

            while (running.get()) {
                directBuf.clear()
                val readStartNs = System.nanoTime()
                val bytesRead = record.read(directBuf, chunkBytes, AudioRecord.READ_BLOCKING)
                val nowNs = System.nanoTime()
                val readDurMs = (nowNs - readStartNs) / 1_000_000L

                if (lastReadEndNs != 0L) {
                    val gapMs = (nowNs - lastReadEndNs) / 1_000_000L
                    if (gapMs > maxReadGapMs) maxReadGapMs = gapMs
                }
                lastReadEndNs = nowNs

                if (bytesRead > 0) {
                    totalReads++
                    if (bytesRead % frameBytes != 0) {
                        misalignedReads++
                    }
                    val framesRead = bytesRead / frameBytes
                    if (framesRead > 0) {
                        val writtenSpscBytes = NativeUsbAudioBridge.pushCapturedPcmFrames(
                            directBuffer = directBuf,
                            frameCount = framesRead,
                            captureFormatCode = NativeUsbAudioBridge.CAPTURE_FMT_FLOAT32_LE
                        )

                        if (totalReads <= 3L) {
                            val tel = NativeUsbAudioBridge.getTelemetry()
                            val l = if (bytesRead >= 8) directBuf.getFloat(0) else 0f
                            val r = if (bytesRead >= 8) directBuf.getFloat(4) else 0f
                            onLog(
                                1,
                                "[CAP-DIAG #$totalReads] bytesRead=$bytesRead, framesRead=$framesRead (readDt=${readDurMs}ms), " +
                                    "sample0=(L=${"%.4f".format(l)}, R=${"%.4f".format(r)}), spscWritten=${writtenSpscBytes}B, " +
                                    "SPSC=${"%.1f".format(tel.bufferFillPercent)}% " +
                                    "(${"%.1f".format(tel.bufferedMs)}/${tel.targetBufferMs}ms)"
                            )
                        } else if (totalReads % healthIntervalReads == 0L) {
                            val tel = NativeUsbAudioBridge.getTelemetry()
                            onLog(
                                1,
                                "[CAP-HEALTH] reads=$totalReads (maxGap=${maxReadGapMs}ms, misalign=$misalignedReads) | " +
                                    "SPSC=${"%.1f".format(tel.bufferedMs)}/${tel.targetBufferMs}ms (min=${"%.1f".format(tel.minBufferedMs)}ms, ${"%.0f".format(tel.bufferFillPercent)}%) | " +
                                    "USB(pkts=${tel.packetsSubmitted}, err=${tel.transferErrors}, underrun=${tel.underruns}, maxGap=${"%.1f".format(tel.maxUsbGapMs)}ms) | " +
                                    "zeroDrops(runs=${tel.zeroDropoutRuns}, blks=${tel.zeroDropoutBlocks}) | " +
                                    "drift(+${tel.driftInsertedFrames}/-${tel.driftDroppedFrames}) | peak=${"%.1f".format(tel.peakDbfs)}dBFS"
                            )
                        }

                        val alignedBytes = framesRead * frameBytes
                        if (!warnedSilence && isBlockAllZero(directBuf, alignedBytes)) {
                            consecutiveZeroBlocks++
                            if (consecutiveZeroBlocks == silenceBlocksThreshold) {
                                warnedSilence = true
                                onLog(
                                    2,
                                    "提示: 已连续 3.6 秒捕获到纯静音。请确认：(1) 目标音乐/音游应用正在播放；" +
                                        "(2) 手机系统媒体音量非 0（建议满格）；(3) 若目标应用声明禁止内录 (allowAudioPlaybackCapture=false) 将无法在无 Root 下捕获。"
                                )
                            }
                        } else if (consecutiveZeroBlocks > 0) {
                            consecutiveZeroBlocks = 0
                        }
                    }
                } else if (bytesRead < 0) {
                    if (running.get()) {
                        val errMsg = "AudioRecord.read 返回错误码 $bytesRead"
                        onLog(3, errMsg)
                        onFatalError(errMsg)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                onLog(3, "AudioRecord 捕获循环异常: ${e.message}")
                onFatalError(e.message ?: "Capture loop exception")
            }
        } finally {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            onLog(
                1,
                "AudioRecord 捕获线程已安全退出 (reads=$totalReads, maxReadGap=${maxReadGapMs}ms, misalign=$misalignedReads)"
            )
        }
    }

    private fun isBlockAllZero(buf: ByteBuffer, len: Int): Boolean {
        val step = maxOf(4, len / 16)
        var i = 0
        while (i + 3 < len) {
            if (buf.getInt(i) != 0) return false
            i += step
        }
        return true
    }
}
