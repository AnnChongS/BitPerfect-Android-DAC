#pragma once

#include "pcm_converter.h"
#include "spsc_ring_buffer.h"
#include "uac_types.h"
#include "usb_iso_streamer.h"

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct libusb_context;
struct libusb_device_handle;

namespace bitperfect {

struct CaptureHardwareConfig {
    uint32_t sampleRate = 0;
    uint8_t bitResolution = 0;
    uint8_t channels = 2;
    int altSetting = 0;
    uac::PcmFormat pcmFormat = uac::PcmFormat::Unknown;
};

class UsbDacEngine {
public:
    UsbDacEngine() = default;
    ~UsbDacEngine();

    UsbDacEngine(const UsbDacEngine&) = delete;
    UsbDacEngine& operator=(const UsbDacEngine&) = delete;

    // Configure crash-proof fsync() USB syscall trace file path for SoC diagnostics
    void setTraceFilePath(const std::string& path);

    // Wraps Android UsbDeviceConnection fd, executes platform-safe USB claim:
    //  - Qualcomm: two-stage (IF#2 -> 4ms silent ISO warmup -> IF#0, skipping IF#1)
    //  - Non-Qualcomm (XRING / Exynos / MediaTek / Tensor): standard offload-safe order
    //    (IF#0 detach first -> 50ms settle -> claim IF#0 & IF#2, ZERO ISO warmup, ZERO clear_halt)
    bool openAndInspect(
        int fd,
        uint16_t fallbackVid,
        uint16_t fallbackPid,
        const std::string& fallbackName,
        double initialVolumeDb,
        bool isQualcommPlatform,
        std::string& outReport,
        std::string& outProfileJson,
        std::string& outError
    );

    void close(bool restoreUnityVolume = true);

    // Phase 2: Start or hot-switch 440Hz reference sine wave at (sampleRate, preferredBitDepth)
    bool startSineTest(uint32_t sampleRate, int preferredBitDepth, std::string& outError);

    // Phase 3: Prepare DAC for live AudioPlaybackCapture bridge:
    //   Stop URBs -> Clear SPSC -> Switch AltSetting & ClockSource (+verify) -> Arm 50% pre-fill gate
    bool prepareCaptureStream(
        uint32_t sampleRate,
        int preferredBitDepth,
        CaptureHardwareConfig& outConfig,
        std::string& outError
    );

    // Phase 3 hot path (lock-free during streaming):
    //   Accepts explicit frameCount (framesRead = bytesRead / srcFrameBytes) so Kotlin & C++
    //   never mismatch byte vs frame units. Converts AudioRecord PCM -> DAC wire format with
    //   NaN/Inf guard + [-1, 1] clamp + dual-clock drift servo, auto-launching 12 USB ISO URBs
    //   once pre-fill reaches safe watermark.
    size_t pushCapturedPcmFrames(const uint8_t* data, size_t frameCount, CapturePcmFormat srcFmt);

    void stopStream();

    // Phase 2 & 4: Hardware volume control (supports Master & Per-Channel FeatureUnit)
    bool setHardwareVolumeDb(double db, std::string& outError);
    double getCurrentVolumeDb() const { return currentVolumeDb_.load(std::memory_order_relaxed); }

    // Ring buffer duration control (60ms low-latency rhythm-game mode - 600ms Hi-Fi mode)
    void setBufferDurationMs(int bufferMs);
    int getBufferDurationMs() const { return bufferDurationMs_.load(std::memory_order_relaxed); }

    void setIsoThreadStartedCallback(IsoThreadStartedCallback cb) {
        isoStreamer_.setThreadStartedCallback(std::move(cb));
    }

    int getIsoThreadTid() const { return isoStreamer_.isoThreadTid(); }

    bool isOpen() const;
    uac::DacDeviceProfile currentProfile() const;
    std::string getTelemetryJson() const;

private:
    void writeTrace(const std::string& step, bool truncate = false) const;
    bool initLibusbContext(std::string& outError);
    void claimAudioInterfacesLocked();
    int controlTransferAcLocked(
        uint8_t bmRequestType,
        uint8_t bRequest,
        uint16_t wValue,
        uint16_t wIndex,
        uint8_t* data,
        uint16_t wLength,
        unsigned int timeoutMs
    );
    void stopStreamLocked();
    void closeLocked(bool restoreUnityVolume = true);

    const uac::AudioStreamAltSetting* selectPlaybackAltLocked(
        uint32_t sampleRate,
        int preferredBitDepth
    ) const;

    bool configureHardwareLocked(
        const uac::AudioStreamAltSetting& alt,
        uint32_t sampleRate,
        std::string& outError
    );

    bool setUac2ClockRateLocked(
        const uac::AudioStreamAltSetting& alt,
        uint32_t sampleRate,
        uint32_t& outVerifiedRate,
        std::string& outError
    );

    bool setUac1ClockRateLocked(
        const uac::AudioStreamAltSetting& alt,
        uint32_t sampleRate,
        uint32_t& outVerifiedRate,
        std::string& outError
    );

    bool setHardwareVolumeDbLocked(double db, std::string& outError);
    void unmuteFeatureUnitLocked();
    void resetAllStreamingInterfacesToAlt0Locked();

    void sineProducerLoop(uint32_t sampleRate, uac::PcmFormat fmt, uint8_t channels);
    size_t computeTargetBufferBytes(uint32_t sampleRate, size_t frameBytes) const;

    mutable std::mutex mutex_;
    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    std::vector<int> claimedInterfaces_;
    uac::DacDeviceProfile profile_;
    bool isOpen_ = false;
    bool isQualcommPlatform_ = false;
    std::string traceFilePath_;

    // Active audio streaming state
    uac::AudioStreamAltSetting activeAlt_{};
    uint32_t activeSampleRate_ = 0;
    bool hardwareConfigured_ = false;

    SpscRingBuffer ringBuffer_{4u * 1024u * 1024u};
    UsbIsoStreamer isoStreamer_;

    // Built-in 440Hz sine test producer
    SineWaveGenerator sineGen_;
    std::atomic<bool> sineRunning_{false};
    std::thread sineThread_;

    // Phase 3 live AudioPlaybackCapture state (producer-thread hot path)
    std::atomic<bool> captureArmed_{false};
    std::atomic<bool> capturePrefilling_{false};
    std::atomic<size_t> captureTargetBytes_{0};
    uac::PcmFormat captureDacFormat_ = uac::PcmFormat::Unknown;
    uint8_t captureDacChannels_ = 2;
    uint32_t captureSampleRate_ = 0;
    std::vector<uint8_t> captureScratch_;
    std::atomic<uint64_t> driftInsertedFrames_{0};
    std::atomic<uint64_t> driftDroppedFrames_{0};
    std::atomic<uint64_t> zeroDropoutRuns_{0};
    std::atomic<uint64_t> zeroDropoutBlocks_{0};
    std::atomic<size_t> minSpscBytesSeen_{SIZE_MAX};
    std::atomic<double> peakDbfs_{-120.0};
    uint32_t driftCooldownBlocks_{0};
    bool prevBlockHadAudio_{false};

    std::atomic<int> bufferDurationMs_{250};
    std::atomic<double> currentVolumeDb_{0.0};
};

} // namespace bitperfect
