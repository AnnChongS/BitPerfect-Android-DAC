package com.bitperfect.usbbridge.nativebridge

import org.json.JSONObject
import java.nio.ByteBuffer

data class PlaybackStreamSpec(
    val interfaceNumber: Int,
    val altSetting: Int,
    val format: String,
    val isPcm: Boolean,
    val channels: Int,
    val subslotSize: Int,
    val bitResolution: Int,
    val dataEndpoint: Int,
    val maxPacketBytes: Int,
    val interval: Int,
    val syncType: String,
    val hasFeedback: Boolean,
    val feedbackEndpoint: Int,
    val clockSourceId: Int,
    val sampleRates: List<Int>
)

data class DacProfile(
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String,
    val productName: String,
    val usbSpeed: String,
    val uacVersion: String,
    val controlInterface: Int,
    val hasHardwareVolume: Boolean,
    val featureUnitId: Int,
    val volumeMinDb: Double?,
    val volumeMaxDb: Double?,
    val playbackStreams: List<PlaybackStreamSpec>
) {
    val vidPidHex: String
        get() = "%04X:%04X".format(vendorId, productId)

    val allSupportedSampleRates: List<Int>
        get() = playbackStreams
            .filter { it.isPcm }
            .flatMap { it.sampleRates }
            .distinct()
            .sorted()

    /**
     * Resolves the closest lossless DAC sample rate for a given source sample rate.
     * Exact match -> Bit-Perfect; otherwise picks smallest supported rate > sourceRate.
     */
    fun resolveDacSampleRate(sourceRate: Int): Pair<Int, Boolean> {
        val rates = allSupportedSampleRates
        if (rates.isEmpty()) return sourceRate to true
        if (sourceRate in rates) return sourceRate to true
        val higher = rates.firstOrNull { it > sourceRate }
        val fallback = higher ?: rates.last()
        return fallback to false
    }
}

data class CaptureHardwareResult(
    val dacSampleRate: Int,
    val dacBitResolution: Int,
    val altSetting: Int,
    val channels: Int,
    val dacFormat: String
) {
    val shortFormatTag: String
        get() = dacFormat.substringBefore(" ")
}

data class StreamTelemetry(
    val streaming: Boolean = false,
    val sineRunning: Boolean = false,
    val captureMode: Boolean = false,
    val capturePrefilling: Boolean = false,
    val sampleRate: Int = 0,
    val bitResolution: Int = 0,
    val format: String = "",
    val syncType: String = "",
    val usingFeedback: Boolean = false,
    val feedbackHz: Int = 0,
    val inFlightUrbs: Int = 0,
    val packetsPerUrb: Int = 0,
    val packetsSubmitted: Long = 0,
    val transferErrors: Long = 0,
    val underruns: Long = 0,
    val bufferedMs: Double = 0.0,
    val minBufferedMs: Double = 0.0,
    val targetBufferMs: Int = 200,
    val bufferFillPercent: Double = 0.0,
    val driftInsertedFrames: Long = 0,
    val driftDroppedFrames: Long = 0,
    val zeroDropoutRuns: Long = 0,
    val zeroDropoutBlocks: Long = 0,
    val maxUsbGapMs: Double = 0.0,
    val peakDbfs: Double = -120.0,
    val isoThreadTid: Int = 0,
    val volumeDb: Double = 0.0
)

sealed class InspectResult {
    data class Success(val profile: DacProfile, val report: String) : InspectResult()
    data class Failure(val error: String) : InspectResult()
}

object NativeUsbAudioBridge {

    // Matches bitperfect::CapturePcmFormat in pcm_converter.h
    const val CAPTURE_FMT_S16_LE = 1
    const val CAPTURE_FMT_S24_3LE = 2
    const val CAPTURE_FMT_S32_LE = 3
    const val CAPTURE_FMT_FLOAT32_LE = 4

    init {
        System.loadLibrary("bitperfect_usb_audio")
    }

    private external fun nativeSetLogListener(listener: NativeLogListener?)

    private external fun nativeSetIsoThreadListener(listener: NativeIsoThreadListener?)

    private external fun nativeGetIsoThreadTid(): Int

    private external fun nativeOpenAndInspect(
        fd: Int,
        vendorId: Int,
        productId: Int,
        fallbackName: String,
        initialVolumeDb: Double
    ): String

    private external fun nativeGetLastReport(): String

    private external fun nativeClose(restoreUnityVolume: Boolean, resetUsbPort: Boolean)

    private external fun nativeStartSineTest(sampleRate: Int, preferredBitDepth: Int): String

    private external fun nativePrepareCaptureStream(sampleRate: Int, preferredBitDepth: Int): String

    private external fun nativePushCapturedPcmFrames(
        directBuffer: ByteBuffer,
        frameCount: Int,
        captureFormatCode: Int
    ): Int

    private external fun nativeStopStream()

    private external fun nativeSetHardwareVolumeDb(db: Double): String

    private external fun nativeSetBufferDurationMs(bufferMs: Int)

    private external fun nativeGetTelemetryJson(): String

    fun setLogListener(listener: NativeLogListener?) {
        nativeSetLogListener(listener)
    }

    fun setIsoThreadListener(listener: NativeIsoThreadListener?) {
        nativeSetIsoThreadListener(listener)
    }

    fun getIsoThreadTid(): Int {
        return nativeGetIsoThreadTid()
    }

    fun openAndInspect(
        fd: Int,
        vendorId: Int,
        productId: Int,
        fallbackName: String,
        initialVolumeDb: Double = 0.0
    ): InspectResult {
        val rawJson = nativeOpenAndInspect(
            fd,
            vendorId,
            productId,
            fallbackName,
            initialVolumeDb
        )
        return try {
            val root = JSONObject(rawJson)
            if (!root.optBoolean("ok", false)) {
                InspectResult.Failure(root.optString("error", "Unknown native error"))
            } else {
                val p = root.getJSONObject("profile")
                val streamsArr = p.getJSONArray("playbackStreams")
                val streams = buildList {
                    for (i in 0 until streamsArr.length()) {
                        val s = streamsArr.getJSONObject(i)
                        val ratesArr = s.getJSONArray("sampleRates")
                        val rates = buildList {
                            for (r in 0 until ratesArr.length()) {
                                add(ratesArr.getInt(r))
                            }
                        }
                        add(
                            PlaybackStreamSpec(
                                interfaceNumber = s.getInt("interfaceNumber"),
                                altSetting = s.getInt("altSetting"),
                                format = s.getString("format"),
                                isPcm = s.getBoolean("isPcm"),
                                channels = s.getInt("channels"),
                                subslotSize = s.getInt("subslotSize"),
                                bitResolution = s.getInt("bitResolution"),
                                dataEndpoint = s.getInt("dataEndpoint"),
                                maxPacketBytes = s.getInt("maxPacketBytes"),
                                interval = s.getInt("interval"),
                                syncType = s.getString("syncType"),
                                hasFeedback = s.getBoolean("hasFeedback"),
                                feedbackEndpoint = s.getInt("feedbackEndpoint"),
                                clockSourceId = s.getInt("clockSourceId"),
                                sampleRates = rates
                            )
                        )
                    }
                }
                val profile = DacProfile(
                    vendorId = p.getInt("vendorId"),
                    productId = p.getInt("productId"),
                    manufacturer = p.optString("manufacturer", ""),
                    productName = p.optString("productName", fallbackName),
                    usbSpeed = p.optString("usbSpeed", "Unknown"),
                    uacVersion = p.optString("uacVersion", "Unknown"),
                    controlInterface = p.optInt("controlInterface", -1),
                    hasHardwareVolume = p.optBoolean("hasHardwareVolume", false),
                    featureUnitId = p.optInt("featureUnitId", -1),
                    volumeMinDb = if (p.has("volumeMinDb")) p.getDouble("volumeMinDb") else null,
                    volumeMaxDb = if (p.has("volumeMaxDb")) p.getDouble("volumeMaxDb") else null,
                    playbackStreams = streams
                )
                InspectResult.Success(profile = profile, report = nativeGetLastReport())
            }
        } catch (e: Exception) {
            InspectResult.Failure("Failed to parse native JSON response: ${e.message}")
        }
    }

    fun startSineTest(sampleRate: Int, preferredBitDepth: Int): Result<Unit> {
        return parseSimpleOkResponse(nativeStartSineTest(sampleRate, preferredBitDepth))
    }

    fun prepareCaptureStream(sampleRate: Int, preferredBitDepth: Int): Result<CaptureHardwareResult> {
        val rawJson = nativePrepareCaptureStream(sampleRate, preferredBitDepth)
        return try {
            val root = JSONObject(rawJson)
            if (!root.optBoolean("ok", false)) {
                Result.failure(IllegalStateException(root.optString("error", "prepareCaptureStream failed")))
            } else {
                Result.success(
                    CaptureHardwareResult(
                        dacSampleRate = root.getInt("dacSampleRate"),
                        dacBitResolution = root.getInt("dacBitResolution"),
                        altSetting = root.getInt("altSetting"),
                        channels = root.getInt("channels"),
                        dacFormat = root.getString("dacFormat")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun pushCapturedPcmFrames(directBuffer: ByteBuffer, frameCount: Int, captureFormatCode: Int): Int {
        return nativePushCapturedPcmFrames(directBuffer, frameCount, captureFormatCode)
    }

    fun stopStream() {
        nativeStopStream()
    }

    fun setHardwareVolumeDb(db: Double): Result<Unit> {
        return parseSimpleOkResponse(nativeSetHardwareVolumeDb(db))
    }

    fun setBufferDurationMs(bufferMs: Int) {
        nativeSetBufferDurationMs(bufferMs)
    }

    fun getTelemetry(): StreamTelemetry {
        return try {
            val j = JSONObject(nativeGetTelemetryJson())
            StreamTelemetry(
                streaming = j.optBoolean("streaming", false),
                sineRunning = j.optBoolean("sineRunning", false),
                captureMode = j.optBoolean("captureMode", false),
                capturePrefilling = j.optBoolean("capturePrefilling", false),
                sampleRate = j.optInt("sampleRate", 0),
                bitResolution = j.optInt("bitResolution", 0),
                format = j.optString("format", ""),
                syncType = j.optString("syncType", ""),
                usingFeedback = j.optBoolean("usingFeedback", false),
                feedbackHz = j.optInt("feedbackHz", 0),
                inFlightUrbs = j.optInt("inFlightUrbs", 0),
                packetsPerUrb = j.optInt("packetsPerUrb", 0),
                packetsSubmitted = j.optLong("packetsSubmitted", 0L),
                transferErrors = j.optLong("transferErrors", 0L),
                underruns = j.optLong("underruns", 0L),
                bufferedMs = j.optDouble("bufferedMs", 0.0),
                minBufferedMs = j.optDouble("minBufferedMs", 0.0),
                targetBufferMs = j.optInt("targetBufferMs", 200),
                bufferFillPercent = j.optDouble("bufferFillPercent", 0.0),
                driftInsertedFrames = j.optLong("driftInsertedFrames", 0L),
                driftDroppedFrames = j.optLong("driftDroppedFrames", 0L),
                zeroDropoutRuns = j.optLong("zeroDropoutRuns", 0L),
                zeroDropoutBlocks = j.optLong("zeroDropoutBlocks", 0L),
                maxUsbGapMs = j.optDouble("maxUsbGapMs", 0.0),
                peakDbfs = j.optDouble("peakDbfs", -120.0),
                isoThreadTid = j.optInt("isoThreadTid", 0),
                volumeDb = j.optDouble("volumeDb", 0.0)
            )
        } catch (_: Exception) {
            StreamTelemetry()
        }
    }

    fun close(restoreUnityVolume: Boolean = true, resetUsbPort: Boolean = false) {
        nativeClose(restoreUnityVolume, resetUsbPort)
    }

    private fun parseSimpleOkResponse(rawJson: String): Result<Unit> {
        return try {
            val root = JSONObject(rawJson)
            if (root.optBoolean("ok", false)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(root.optString("error", "Native call failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
