package com.bitperfect.rootdac.capture

import android.os.Process
import com.bitperfect.rootdac.nativebridge.NativeUsbAudioBridge
import com.bitperfect.rootdac.xposed.AppleMusicAudioTrackHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class LspSourceFormatEvent(
    val sampleRate: Int,
    val formatCode: Int,
    val sourceBitDepth: Int,
    val encodingName: String,
    val rawChannels: Int,
    val trackId: Int,
    val pid: Int,
    val routeSummary: String = ""
) {
    fun sameHardwareSpec(other: LspSourceFormatEvent?): Boolean {
        return other != null &&
            sampleRate == other.sampleRate &&
            formatCode == other.formatCode &&
            sourceBitDepth == other.sourceBitDepth
    }
}

data class LspConnectionStatus(
    val listening: Boolean = false,
    val clientConnected: Boolean = false,
    val connectedPids: Set<Int> = emptySet(),
    val connectedProcess: String = "",
    val activeFormat: LspSourceFormatEvent? = null,
    val totalFramesReceived: Long = 0L
)

/**
 * Localhost (`127.0.0.1:39528`) Bit-Perfect TCP Receiver for [AppleMusicAudioTrackHook].
 */
class LspDirectServer(
    private val onLog: (level: Int, msg: String) -> Unit
) {
    companion object {
        private const val DIRECT_BUF_BYTES = 262144 // 256 KB DirectByteBuffer
        private const val MAX_PACKET_BYTES = 524288 // 512 KB sanity cap
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val activeClients = ConcurrentHashMap<Socket, Int>()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var configuredFormat: LspSourceFormatEvent? = null

    private val _status = MutableStateFlow(LspConnectionStatus())
    val status: StateFlow<LspConnectionStatus> = _status.asStateFlow()

    val isRunning: Boolean
        get() = running.get()

    fun start(
        onPrepareHardwareForFormat: suspend (LspSourceFormatEvent) -> Boolean,
        onFlushRequested: suspend (String) -> Unit
    ): Result<Unit> {
        stop()

        return try {
            val bindAddr = InetAddress.getByName(AppleMusicAudioTrackHook.BRIDGE_HOST)
            val srv = ServerSocket(AppleMusicAudioTrackHook.BRIDGE_PORT, 4, bindAddr).apply {
                reuseAddress = true
            }
            serverSocket = srv
            configuredFormat = null
            running.set(true)
            _status.value = LspConnectionStatus(listening = true)

            onLog(
                1,
                "LSPosed 源码直截监听已启动 (127.0.0.1:${AppleMusicAudioTrackHook.BRIDGE_PORT}, TCP_NODELAY, 零 r_submix / 零僵尸路由)"
            )

            acceptJob = scope.launch {
                while (isActive && running.get()) {
                    val client = try {
                        srv.accept()
                    } catch (_: Exception) {
                        if (!running.get()) break else continue
                    }
                    launch {
                        handleClientSocket(client, onPrepareHardwareForFormat, onFlushRequested)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            running.set(false)
            _status.value = LspConnectionStatus(listening = false)
            Result.failure(e)
        }
    }

    fun resetConfiguredFormat() {
        configuredFormat = null
    }

    fun stop() {
        val wasRunning = running.getAndSet(false)
        acceptJob?.cancel()
        acceptJob = null

        runCatching { serverSocket?.close() }
        serverSocket = null

        for (sock in activeClients.keys) {
            runCatching { sock.close() }
        }
        activeClients.clear()
        configuredFormat = null
        _status.value = LspConnectionStatus(listening = false)

        if (wasRunning) {
            onLog(1, "LSPosed 源码直截 TCP 服务已关闭 (Apple Music 将自动恢复手机扬声器路由与音量)")
        }
    }

    private suspend fun handleClientSocket(
        socket: Socket,
        onPrepareHardwareForFormat: suspend (LspSourceFormatEvent) -> Boolean,
        onFlushRequested: suspend (String) -> Unit
    ) {
        runCatching {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 524288
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }

        activeClients[socket] = 0
        refreshConnectionStatus()

        val directBuf = ByteBuffer.allocateDirect(DIRECT_BUF_BYTES).order(ByteOrder.nativeOrder())
        val heapReadBuf = ByteArray(DIRECT_BUF_BYTES)
        var framesCounter = 0L
        var warnedMultichannel = false

        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 131072))
            while (running.get()) {
                val magic = input.readInt()
                if (magic != AppleMusicAudioTrackHook.PROTOCOL_MAGIC) {
                    onLog(2, "LSPosed 协议帧头校验不匹配 (0x${magic.toUInt().toString(16)})，断开重连")
                    break
                }

                val msgType = input.readByte()
                val formatCode = input.readByte().toInt() and 0xFF
                val sourceBitDepth = input.readByte().toInt() and 0xFF
                val channels = input.readByte().toInt() and 0xFF
                val sampleRate = input.readInt()
                val trackIdOrPid = input.readInt()
                val payloadLen = input.readInt()

                if (payloadLen < 0 || payloadLen > MAX_PACKET_BYTES) {
                    onLog(2, "LSPosed 协议负载长度异常 ($payloadLen B)，断开重连")
                    break
                }

                when (msgType) {
                    AppleMusicAudioTrackHook.MSG_HELLO -> {
                        val meta = readStringPayload(input, payloadLen)
                        val pid = parseMetaInt(meta, "pid", trackIdOrPid)
                        val proc = parseMetaStr(meta, "proc", AppleMusicAudioTrackHook.TARGET_PACKAGE)
                        val prevPid = activeClients.put(socket, pid)
                        if (prevPid == null || prevPid == 0) {
                            onLog(1, "✓ LSPosed 模块已握手连接: $proc (PID=$pid)")
                        }
                        refreshConnectionStatus(processName = proc)
                    }

                    AppleMusicAudioTrackHook.MSG_FORMAT_CONFIG -> {
                        val meta = readStringPayload(input, payloadLen)
                        val encName = parseMetaStr(meta, "enc", formatCodeToName(formatCode))
                        val rawCh = parseMetaInt(meta, "rawCh", channels)
                        val pid = parseMetaInt(meta, "pid", activeClients[socket] ?: 0)
                        val routeStr = parseMetaStr(meta, "route", "unknown")
                        if (pid > 0) activeClients[socket] = pid

                        if (rawCh > 2 && !warnedMultichannel) {
                            warnedMultichannel = true
                            onLog(
                                2,
                                "检测到 Apple Music 输出 ${rawCh} 声道音频（可能开启了杜比全景声），已自动提取前左/前右双声道；建议关闭杜比全景声以获取纯正双声道 Hi-Res 192kHz 源码。"
                            )
                        } else if (rawCh <= 2) {
                            warnedMultichannel = false
                        }

                        val evt = LspSourceFormatEvent(
                            sampleRate = sampleRate,
                            formatCode = formatCode,
                            sourceBitDepth = sourceBitDepth,
                            encodingName = encName,
                            rawChannels = rawCh,
                            trackId = trackIdOrPid,
                            pid = pid,
                            routeSummary = routeStr
                        )
                        onLog(
                            1,
                            "[LSP] 收到音轨配置: ${evt.sourceBitDepth}-bit/${evt.sampleRate}Hz (${evt.encodingName}), 静音时钟锚定路由=${evt.routeSummary}"
                        )
                        if (!evt.sameHardwareSpec(configuredFormat)) {
                            val ok = onPrepareHardwareForFormat(evt)
                            if (ok) {
                                configuredFormat = evt
                            }
                        } else if (configuredFormat?.trackId != evt.trackId) {
                            configuredFormat = evt
                            NativeUsbAudioBridge.flushCaptureBuffer()
                        }
                        refreshConnectionStatus(activeFormat = evt)
                    }

                    AppleMusicAudioTrackHook.MSG_FLUSH -> {
                        val reason = readStringPayload(input, payloadLen)
                        val cur = configuredFormat
                        if (cur == null || cur.trackId == 0 || cur.trackId == trackIdOrPid) {
                            onFlushRequested(reason)
                        }
                    }

                    AppleMusicAudioTrackHook.MSG_PCM_DATA -> {
                        if (payloadLen == 0) continue

                        val curCfg = configuredFormat
                        if (curCfg == null ||
                            curCfg.sampleRate != sampleRate ||
                            curCfg.formatCode != formatCode
                        ) {
                            val autoEvt = LspSourceFormatEvent(
                                sampleRate = sampleRate,
                                formatCode = formatCode,
                                sourceBitDepth = if (sourceBitDepth > 0) sourceBitDepth else 24,
                                encodingName = formatCodeToName(formatCode),
                                rawChannels = channels,
                                trackId = trackIdOrPid,
                                pid = activeClients[socket] ?: 0
                            )
                            val ok = onPrepareHardwareForFormat(autoEvt)
                            if (ok) {
                                configuredFormat = autoEvt
                                refreshConnectionStatus(activeFormat = autoEvt)
                            }
                        }

                        val bps = bytesPerSample(formatCode)
                        val stereoFrameBytes = bps * 2
                        if (stereoFrameBytes <= 0) {
                            skipFully(input, payloadLen)
                            continue
                        }

                        var remaining = payloadLen
                        while (remaining > 0) {
                            val chunkBytes = minOf(remaining, DIRECT_BUF_BYTES)
                            input.readFully(heapReadBuf, 0, chunkBytes)
                            remaining -= chunkBytes

                            val wholeFrames = chunkBytes / stereoFrameBytes
                            if (wholeFrames <= 0) continue
                            val validBytes = wholeFrames * stereoFrameBytes

                            // Zero-allocation 20ns primitive JNI check: apply smooth TCP flow control
                            // only if 4MB SPSC buffer exceeds ~1100ms (never drops frames!)
                            var guardSpins = 0
                            while (running.get() && guardSpins < 30) {
                                val bufMs = NativeUsbAudioBridge.getBufferedDurationMs()
                                if (bufMs < 1100.0) break
                                Thread.sleep(4L)
                                guardSpins++
                            }

                            directBuf.clear()
                            directBuf.put(heapReadBuf, 0, validBytes)
                            directBuf.flip()

                            NativeUsbAudioBridge.pushCapturedPcmFrames(
                                directBuffer = directBuf,
                                frameCount = wholeFrames,
                                captureFormatCode = formatCode
                            )
                            framesCounter += wholeFrames
                        }

                        if ((framesCounter and 0x7FFFL) == 0L) {
                            _status.value = _status.value.copy(totalFramesReceived = framesCounter)
                        }
                    }

                    else -> {
                        skipFully(input, payloadLen)
                    }
                }
            }
        } catch (_: EOFException) {
        } catch (e: Exception) {
            if (running.get()) {
                onLog(2, "LSPosed 客户端连接断开: ${e.message}")
            }
        } finally {
            val removedPid = activeClients.remove(socket)
            runCatching { socket.close() }
            if (running.get() && removedPid != null && removedPid > 0) {
                onLog(1, "Apple Music 进程 (PID=$removedPid) 已断开连接")
            }
            refreshConnectionStatus()
        }
    }

    private fun refreshConnectionStatus(
        processName: String? = null,
        activeFormat: LspSourceFormatEvent? = null
    ) {
        val pids = activeClients.values.filter { it > 0 }.toSet()
        val prev = _status.value
        val connected = activeClients.isNotEmpty()
        _status.value = prev.copy(
            listening = running.get(),
            clientConnected = connected,
            connectedPids = pids,
            connectedProcess = processName ?: if (connected) prev.connectedProcess else "",
            activeFormat = activeFormat ?: if (connected) prev.activeFormat else null
        )
    }

    private fun readStringPayload(input: DataInputStream, len: Int): String {
        if (len <= 0) return ""
        val buf = ByteArray(len)
        input.readFully(buf)
        return String(buf, StandardCharsets.UTF_8)
    }

    private fun skipFully(input: DataInputStream, len: Int) {
        var rem = len
        val scratch = ByteArray(minOf(rem, 4096))
        while (rem > 0) {
            val n = minOf(rem, scratch.size)
            input.readFully(scratch, 0, n)
            rem -= n
        }
    }

    private fun parseMetaStr(meta: String, key: String, fallback: String): String {
        val prefix = "$key="
        for (part in meta.split(';')) {
            if (part.startsWith(prefix)) {
                return part.removePrefix(prefix)
            }
        }
        return fallback
    }

    private fun parseMetaInt(meta: String, key: String, fallback: Int): Int {
        return parseMetaStr(meta, key, "").toIntOrNull() ?: fallback
    }

    private fun bytesPerSample(formatCode: Int): Int = when (formatCode) {
        NativeUsbAudioBridge.CAPTURE_FMT_S16_LE -> 2
        NativeUsbAudioBridge.CAPTURE_FMT_S24_3LE -> 3
        NativeUsbAudioBridge.CAPTURE_FMT_S32_LE,
        NativeUsbAudioBridge.CAPTURE_FMT_FLOAT32_LE -> 4
        else -> 0
    }

    private fun formatCodeToName(formatCode: Int): String = when (formatCode) {
        NativeUsbAudioBridge.CAPTURE_FMT_S16_LE -> "PCM_16BIT"
        NativeUsbAudioBridge.CAPTURE_FMT_S24_3LE -> "PCM_24BIT_PACKED"
        NativeUsbAudioBridge.CAPTURE_FMT_S32_LE -> "PCM_32BIT"
        NativeUsbAudioBridge.CAPTURE_FMT_FLOAT32_LE -> "PCM_FLOAT"
        else -> "PCM_$formatCode"
    }
}
