package com.bitperfect.rootdac.capture

import android.content.Context
import android.media.AudioFormat
import android.os.Process as AndroidProcess
import com.bitperfect.rootdac.nativebridge.NativeUsbAudioBridge
import com.bitperfect.rootdac.root.RootLoopbackDaemon
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ActiveCaptureConfig(
    val sampleRate: Int,
    val androidEncoding: Int,
    val encodingName: String,
    val nativeFormatCode: Int,
    val bytesPerSample: Int,
    val channels: Int = 2,
    val recordBufferBytes: Int = 0
)

/**
 * Pure Root Exclusive Loopback Capture Pipeline (AudioPolicy ROUTE_FLAG_LOOP_BACK, NO RENDER).
 *
 * Launches [RootLoopbackDaemon] as uid=0 via `su -c "CLASSPATH=<apk> exec app_process64 ..."`
 * so Apple Music is routed directly into a dedicated r_submix MixerThread at the exact native
 * sample rate — completely bypassing DuplicatingThread, Speaker DSP/MiSound/Dolby, software
 * volume attenuation (SUBMIX_FIXED_VOLUME 0.0 dB), and FLAG_DEEP_BUFFER dropouts.
 */
class RootLoopbackPipeline(
    private val appContext: Context,
    private val onLog: (level: Int, msg: String) -> Unit
) {
    companion object {
        private const val CHANNELS = 2
        private const val HANDSHAKE_TIMEOUT_MS = 5000L
        private const val MAX_PACKET_BYTES = 256 * 1024
    }

    private val running = AtomicBoolean(false)
    @Volatile
    private var daemonProcess: Process? = null
    @Volatile
    private var readerThread: Thread? = null
    @Volatile
    private var stderrThread: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    fun startCapture(
        targetUid: Int,
        sampleRate: Int,
        onCaptureThreadStarted: (tid: Int) -> Unit,
        onFatalError: (String) -> Unit
    ): Result<ActiveCaptureConfig> {
        stopCapture()

        val apkPath = appContext.applicationInfo.sourceDir
        if (apkPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException("无法获取当前 APK 路径 (sourceDir 为空)"))
        }

        val escapedApk = apkPath.replace("'", "'\"'\"'")
        val shellCmd = "APP_PROC=/system/bin/app_process64; " +
            "[ -x \"\$APP_PROC\" ] || APP_PROC=/system/bin/app_process; " +
            "CLASSPATH='$escapedApk' exec \"\$APP_PROC\" /system/bin " +
            "--nice-name=bitperfect_rootdac_loopback " +
            "${RootLoopbackDaemon::class.java.name} $targetUid $sampleRate"

        val proc = try {
            ProcessBuilder("su", "-c", shellCmd)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            val msg = "启动 Root 环回守护进程失败: ${e.message}"
            onLog(3, msg)
            return Result.failure(IllegalStateException(msg, e))
        }

        daemonProcess = proc
        running.set(true)

        val lastErrLine = AtomicReference("")
        val errThread = Thread({
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val trimmed = line?.trim().orEmpty()
                        if (trimmed.isEmpty()) continue
                        lastErrLine.set(trimmed)
                        val lvl = when {
                            trimmed.contains("FATAL", ignoreCase = true) ||
                                trimmed.contains("FAILED", ignoreCase = true) ||
                                trimmed.contains("Exception", ignoreCase = true) -> 3
                            trimmed.contains("retrying", ignoreCase = true) ||
                                trimmed.contains("failed", ignoreCase = true) -> 2
                            else -> 1
                        }
                        onLog(lvl, "[ROOT-LOOPBACK] $trimmed")
                    }
                }
            } catch (_: Throwable) {
                // Process closed
            }
        }, "BP_RootDaemonErr")
        stderrThread = errThread
        errThread.start()

        val handshakeLatch = CountDownLatch(1)
        val handshakeResult = AtomicReference<Result<ActiveCaptureConfig>?>(null)

        val rdThread = Thread({
            readLoop(
                proc = proc,
                targetUid = targetUid,
                handshakeLatch = handshakeLatch,
                handshakeResult = handshakeResult,
                lastErrLine = lastErrLine,
                onCaptureThreadStarted = onCaptureThreadStarted,
                onFatalError = onFatalError
            )
        }, "BP_RootPcmPipe")
        readerThread = rdThread
        rdThread.start()

        val completed = try {
            handshakeLatch.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }

        if (!completed) {
            val errDetail = lastErrLine.get().ifBlank { "等待 RootLoopbackDaemon 握手超时 (${HANDSHAKE_TIMEOUT_MS}ms)" }
            onLog(3, "Root 独占环回启动超时: $errDetail")
            stopCapture()
            return Result.failure(IllegalStateException("Root 独占环回启动超时: $errDetail"))
        }

        val res = handshakeResult.get()
            ?: Result.failure(IllegalStateException("Root 独占环回握手返回空结果"))

        if (res.isFailure) {
            stopCapture()
        }
        return res
    }

    fun stopCapture() {
        running.set(false)
        val proc = daemonProcess
        daemonProcess = null

        if (proc != null) {
            // Closing stdin triggers clean AudioPolicy unregistration inside RootLoopbackDaemon!
            runCatching {
                proc.outputStream.write('Q'.code)
                proc.outputStream.flush()
            }
            runCatching { proc.outputStream.close() }
            runCatching {
                if (!proc.waitFor(600, TimeUnit.MILLISECONDS)) {
                    proc.destroy()
                    if (!proc.waitFor(300, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }
            runCatching { proc.inputStream.close() }
            runCatching { proc.errorStream.close() }
        }

        val rd = readerThread
        readerThread = null
        if (rd != null && rd !== Thread.currentThread()) {
            runCatching { rd.join(800) }
        }

        val err = stderrThread
        stderrThread = null
        if (err != null && err !== Thread.currentThread()) {
            runCatching { err.join(300) }
        }
    }

    private fun readLoop(
        proc: Process,
        targetUid: Int,
        handshakeLatch: CountDownLatch,
        handshakeResult: AtomicReference<Result<ActiveCaptureConfig>?>,
        lastErrLine: AtomicReference<String>,
        onCaptureThreadStarted: (tid: Int) -> Unit,
        onFatalError: (String) -> Unit
    ) {
        try {
            AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {}

        var totalReads = 0L
        var misalignedReads = 0L
        var maxReadGapMs = 0L
        var lastReadEndNs = 0L
        var handshakeDone = false

        try {
            val input = BufferedInputStream(proc.inputStream, 65536)

            // Sync on 4-byte "BPPH" handshake magic
            syncToMagic(input, RootLoopbackDaemon.MAGIC_HEADER)

            val hdrTail = ByteArray(16)
            readFully(input, hdrTail, 0, 16)
            val actualRate = readLeInt(hdrTail, 0)
            val nativeFmtCode = readLeInt(hdrTail, 4)
            val recordBufBytes = readLeInt(hdrTail, 8)
            val daemonCaptureTid = readLeInt(hdrTail, 12)

            val (androidEnc, bytesPerSample, encName) = formatMetadata(nativeFmtCode)
            val frameBytes = bytesPerSample * CHANNELS

            val cfg = ActiveCaptureConfig(
                sampleRate = actualRate,
                androidEncoding = androidEnc,
                encodingName = "RootLoopback $encName (${frameBytes}B/frame, FixedVol 0dB)",
                nativeFormatCode = nativeFmtCode,
                bytesPerSample = bytesPerSample,
                channels = CHANNELS,
                recordBufferBytes = recordBufBytes
            )

            handshakeResult.set(Result.success(cfg))
            handshakeDone = true
            handshakeLatch.countDown()

            onLog(
                1,
                "Root 独占环回已就绪: UID=$targetUid | $actualRate Hz | ${cfg.encodingName} | " +
                    "AudioRecordBuf=${recordBufBytes}B | daemonTid=$daemonCaptureTid (无 DEEP_BUFFER / 扬声器静音)"
            )
            if (daemonCaptureTid > 0) {
                onCaptureThreadStarted(daemonCaptureTid)
            }

            var directBuf = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())
            var readScratch = ByteArray(65536)
            val frameHdr = ByteArray(8)
            var consecutiveZeroBlocks = 0
            var warnedSilence = false

            while (running.get()) {
                val readStartNs = System.nanoTime()
                readFully(input, frameHdr, 0, 8)
                val magic = readLeInt(frameHdr, 0)
                val payloadBytes = readLeInt(frameHdr, 4)

                if (magic != RootLoopbackDaemon.MAGIC_DATA || payloadBytes <= 0 || payloadBytes > MAX_PACKET_BYTES) {
                    throw IllegalStateException(
                        "Root 环回帧头同步异常 (magic=0x${magic.toString(16)}, len=$payloadBytes)"
                    )
                }

                if (payloadBytes > readScratch.size) {
                    readScratch = ByteArray(payloadBytes)
                    directBuf = ByteBuffer.allocateDirect(payloadBytes).order(ByteOrder.nativeOrder())
                }

                readFully(input, readScratch, 0, payloadBytes)
                val nowNs = System.nanoTime()
                val readDurMs = (nowNs - readStartNs) / 1_000_000L

                if (lastReadEndNs != 0L) {
                    val gapMs = (nowNs - lastReadEndNs) / 1_000_000L
                    if (gapMs > maxReadGapMs) maxReadGapMs = gapMs
                }
                lastReadEndNs = nowNs

                totalReads++
                if (payloadBytes % frameBytes != 0) {
                    misalignedReads++
                }
                val framesRead = payloadBytes / frameBytes
                if (framesRead <= 0) continue

                val alignedBytes = framesRead * frameBytes
                directBuf.clear()
                directBuf.put(readScratch, 0, alignedBytes)
                directBuf.flip()

                val writtenSpscBytes = NativeUsbAudioBridge.pushCapturedPcmFrames(
                    directBuffer = directBuf,
                    frameCount = framesRead,
                    captureFormatCode = nativeFmtCode
                )

                if (totalReads <= 3L) {
                    val tel = NativeUsbAudioBridge.getTelemetry()
                    val sampleStr = if (nativeFmtCode == NativeUsbAudioBridge.CAPTURE_FMT_FLOAT32_LE && alignedBytes >= 8) {
                        "L=${"%.4f".format(directBuf.getFloat(0))}, R=${"%.4f".format(directBuf.getFloat(4))}"
                    } else {
                        "fmt=$nativeFmtCode"
                    }
                    onLog(
                        1,
                        "[CAP-DIAG #$totalReads] bytesRead=$alignedBytes, framesRead=$framesRead (readDt=${readDurMs}ms), " +
                            "sample0=($sampleStr), spscWritten=${writtenSpscBytes}B, " +
                            "SPSC=${"%.1f".format(tel.bufferFillPercent)}% (${"%.1f".format(tel.bufferedMs)}/${tel.targetBufferMs}ms)"
                    )
                } else if (totalReads % 250L == 0L) {
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

                if (!warnedSilence && isAllZero(readScratch, alignedBytes)) {
                    consecutiveZeroBlocks++
                    if (consecutiveZeroBlocks == 180) {
                        warnedSilence = true
                        onLog(
                            2,
                            "提示: Root 独占环回已连续 3.6 秒收到静音。请确认 Apple Music 正在播放（如刚启动直通，可暂停再播放或切一首歌触发音轨重路由）。"
                        )
                    }
                } else if (consecutiveZeroBlocks > 0) {
                    consecutiveZeroBlocks = 0
                }
            }
        } catch (e: Exception) {
            val detail = lastErrLine.get().ifBlank { e.message ?: e.javaClass.simpleName }
            if (!handshakeDone) {
                handshakeResult.set(Result.failure(IllegalStateException("Root 独占环回初始化失败: $detail", e)))
                handshakeLatch.countDown()
            } else if (running.get()) {
                val msg = "Root 独占环回管道中断: $detail"
                onLog(3, msg)
                onFatalError(msg)
            }
        } finally {
            if (!handshakeDone) {
                handshakeResult.set(Result.failure(IllegalStateException("Root 独占环回进程提前退出")))
                handshakeLatch.countDown()
            }
            if (totalReads > 0L) {
                onLog(
                    1,
                    "Root 独占环回接收线程已安全退出 (reads=$totalReads, maxReadGap=${maxReadGapMs}ms, misalign=$misalignedReads)"
                )
            }
        }
    }

    private fun syncToMagic(input: InputStream, expectedMagic: Int) {
        var window = 0
        var scanned = 0
        while (running.get() && scanned < 4096) {
            val b = input.read()
            if (b == -1) throw EOFException("Daemon stdout EOF before handshake")
            window = (window ushr 8) or ((b and 0xFF) shl 24)
            scanned++
            if (scanned >= 4 && window == expectedMagic) {
                return
            }
        }
        throw IllegalStateException("未检测到 RootLoopbackDaemon 握手帧 (scanned=$scanned)")
    }

    private fun readFully(input: InputStream, dst: ByteArray, offset: Int, length: Int) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val n = input.read(dst, pos, end - pos)
            if (n < 0) throw EOFException("Daemon PCM stream EOF")
            pos += n
        }
    }

    private fun readLeInt(src: ByteArray, offset: Int): Int {
        return (src[offset].toInt() and 0xFF) or
            ((src[offset + 1].toInt() and 0xFF) shl 8) or
            ((src[offset + 2].toInt() and 0xFF) shl 16) or
            ((src[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun formatMetadata(nativeFmtCode: Int): Triple<Int, Int, String> {
        return when (nativeFmtCode) {
            NativeUsbAudioBridge.CAPTURE_FMT_FLOAT32_LE ->
                Triple(AudioFormat.ENCODING_PCM_FLOAT, 4, "PCM_FLOAT")
            NativeUsbAudioBridge.CAPTURE_FMT_S32_LE ->
                Triple(AudioFormat.ENCODING_PCM_32BIT, 4, "PCM_32BIT")
            NativeUsbAudioBridge.CAPTURE_FMT_S24_3LE ->
                Triple(AudioFormat.ENCODING_PCM_24BIT_PACKED, 3, "PCM_24BIT_PACKED")
            else ->
                Triple(AudioFormat.ENCODING_PCM_16BIT, 2, "PCM_16BIT")
        }
    }

    private fun isAllZero(buf: ByteArray, length: Int): Boolean {
        val step = maxOf(1, length / 64)
        var i = 0
        while (i < length) {
            if (buf[i] != 0.toByte()) return false
            i += step
        }
        return true
    }
}
