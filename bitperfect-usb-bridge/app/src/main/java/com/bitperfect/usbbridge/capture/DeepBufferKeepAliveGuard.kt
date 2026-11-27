package com.bitperfect.usbbridge.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure Non-Root Qualcomm PAL / AudioFlinger DEEP_BUFFER_PLAYBACK Pre-Warmer & Anti-Standby Guard.
 *
 * Root cause solved:
 *  - On Snapdragon / HyperOS Android 14-17, MediaProjection (ROUTE_FLAG_LOOP_BACK_RENDER) attaches
 *    Apple Music (FLAG_DEEP_BUFFER) to a DuplicatingThread driven by IoHandle: 21 (PAL_STREAM_DEEP_BUFFER).
 *  - If snd-usb-audio is detached BEFORE IoHandle: 21 opens PAL_STREAM_DEEP_BUFFER — OR if Apple Music
 *    pauses / switches audio sources so IoHandle: 21 enters Standby (2.0s timeout) and later re-opens —
 *    AHAL_StreamOut_QTI calls `pal_stream_open(PAL_STREAM_DEEP_BUFFER)` against the detached USB ALSA card,
 *    failing repeatedly (`pal stream open failed!!!`) and injecting zeroDrops into r_submix!
 *
 * How this guard works (100% Non-Root, zero speaker bleed, zero bits added to captured audio):
 *  1. Before libusb detaches snd-usb-audio, starts an all-zero (0.0f) AudioTrack with
 *     `PERFORMANCE_MODE_POWER_SAVING` (forces AUDIO_OUTPUT_FLAG_DEEP_BUFFER -> IoHandle: 21) and
 *     `ALLOW_CAPTURE_BY_NONE` (excluded from AudioPlaybackCapture / r_submix!).
 *  2. Pre-warms for ~260ms while snd-usb-audio is still attached so `pal_stream_open` succeeds cleanly.
 *  3. Keeps pumping digital zeros continuously so IoHandle: 21 NEVER enters Standby across pauses or
 *     track/source switches until the user explicitly releases the USB DAC.
 */
class DeepBufferKeepAliveGuard(
    private val onLog: (level: Int, msg: String) -> Unit
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    val isRunning: Boolean
        get() = running.get()

    suspend fun ensureStartedAndPreWarmed(preWarmMs: Long = 260L): Boolean {
        if (running.get() && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            onLog(1, "[PAL-GUARD] DEEP_BUFFER 保活音轨已在运行中 (IoHandle 保持非休眠态)")
            return true
        }
        val started = startInternal()
        if (started && preWarmMs > 0L) {
            onLog(
                1,
                "[PAL-GUARD] 正在趁 snd-usb-audio 未剥离前预热 PAL_STREAM_DEEP_BUFFER (${preWarmMs}ms)..."
            )
            delay(preWarmMs)
            onLog(1, "[PAL-GUARD] PAL_STREAM_DEEP_BUFFER 预热完成 (mPalStreamHandle 已锁定开启)")
        }
        return started
    }

    private fun startInternal(): Boolean {
        stop()

        val sampleRate = 48000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onLog(2, "[PAL-GUARD] getMinBufferSize 失败 ($minBuf)")
            return false
        }

        // >= 200ms stereo 16-bit buffer + PERFORMANCE_MODE_POWER_SAVING guarantees FLAG_DEEP_BUFFER routing
        val target200msBytes = (sampleRate / 5) * 4
        val bufferBytes = maxOf(minBuf * 2, target200msBytes)

        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()

            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                onLog(2, "[PAL-GUARD] DEEP_BUFFER AudioTrack 初始化失败")
                return false
            }

            audioTrack = track
            running.set(true)

            val zeroChunk = ByteArray((sampleRate / 25) * 4) // 40ms of exact 0s (7680B @ 48kHz)
            val t = Thread(
                {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                        track.play()
                        onLog(
                            1,
                            "[PAL-GUARD] DEEP_BUFFER 防休眠保活已启动 (48kHz, ALLOW_CAPTURE_BY_NONE, buf=${bufferBytes}B)"
                        )
                        while (running.get()) {
                            val written = track.write(zeroChunk, 0, zeroChunk.size, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) {
                                if (running.get()) {
                                    onLog(2, "[PAL-GUARD] AudioTrack.write 返回 $written, 保活线程退出")
                                }
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            onLog(2, "[PAL-GUARD] 保活线程异常: ${e.message}")
                        }
                    } finally {
                        runCatching {
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                track.stop()
                            }
                        }
                    }
                },
                "BP_DeepBufKeepAlive"
            )
            workerThread = t
            t.start()
            true
        } catch (e: Exception) {
            onLog(2, "[PAL-GUARD] 创建 DEEP_BUFFER 保活失败: ${e.message}")
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && audioTrack == null) return
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            }
        }
        val t = workerThread
        workerThread = null
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(800) }
        }
        if (track != null) {
            runCatching { track.release() }
        }
        onLog(1, "[PAL-GUARD] DEEP_BUFFER 防休眠保活已停止")
    }
}
