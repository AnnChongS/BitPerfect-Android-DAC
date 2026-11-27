#pragma once

#include "spsc_ring_buffer.h"
#include "uac_types.h"

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <thread>
#include <vector>

struct libusb_context;
struct libusb_device_handle;
struct libusb_transfer;

namespace bitperfect {

/**
 * Pure packet-frame pacer supporting BOTH:
 * 1) Sync=Adaptive / Sync=Synchronous (zero-drift exact integer rational accumulator):
 *      acc += sampleRate; frames = acc / intervalsPerSec; acc %= intervalsPerSec;
 * 2) Sync=Asynchronous with explicit Feedback Endpoint (10.14 FS / 16.16 HS fixed-point):
 *      accFp1616 += activeRateFp1616; frames = accFp1616 >> 16; accFp1616 &= 0xFFFF;
 *
 * Kept header-only so both the real-time USB callback and host unit tests share exact math.
 */
class IsoPacketPacer {
public:
    void configure(uint32_t sampleRate, int usbSpeed, uint8_t bInterval,
                   uint32_t frameBytes, uint32_t maxPacketBytes, bool useAsyncFeedback) {
        sampleRate_ = sampleRate;
        frameBytes_ = frameBytes > 0 ? frameBytes : 4u;
        maxPacketBytes_ = maxPacketBytes;
        maxFramesPerPacket_ = maxPacketBytes_ / frameBytes_;
        useAsyncFeedback_ = useAsyncFeedback;

        // USB 2.0 HS / SuperSpeed: 125us microframes (8000/s); FS: 1ms frames (1000/s)
        isHighSpeed_ = (usbSpeed >= 3); // LIBUSB_SPEED_HIGH == 3
        uint32_t clampedInterval = (bInterval >= 1 && bInterval <= 16) ? bInterval : 1u;
        intervalShift_ = clampedInterval - 1u;
        uint32_t baseRate = isHighSpeed_ ? 8000u : 1000u;
        intervalsPerSec_ = std::max<uint32_t>(1u, baseRate >> intervalShift_);

        // Exact integer rational accumulator (Adaptive / Sync)
        rationalAcc_ = 0;

        // Nominal 16.16 fixed-point frames per service interval (Async + Feedback)
        nominalFp1616_ = static_cast<uint32_t>(
            ((static_cast<uint64_t>(sampleRate_) << 16) + (intervalsPerSec_ / 2u)) / intervalsPerSec_
        );
        activeFp1616_.store(nominalFp1616_, std::memory_order_relaxed);
        fracAccFp1616_ = 0;
        lastFeedbackHz_.store(sampleRate_, std::memory_order_relaxed);
    }

    // Hot-path: called once per microframe/frame inside Isochronous OUT URB refill
    uint32_t nextPacketBytes() {
        uint32_t frames = 0;
        if (useAsyncFeedback_) {
            const uint32_t rateFp = activeFp1616_.load(std::memory_order_relaxed);
            const uint64_t next = static_cast<uint64_t>(fracAccFp1616_) + static_cast<uint64_t>(rateFp);
            frames = static_cast<uint32_t>(next >> 16);
            fracAccFp1616_ = static_cast<uint32_t>(next & 0xFFFFu);
        } else {
            // Exact rational phase accumulator (0 ppm drift for 44.1k / 48k / 96k / 192k / 384k)
            rationalAcc_ += sampleRate_;
            frames = static_cast<uint32_t>(rationalAcc_ / intervalsPerSec_);
            rationalAcc_ %= intervalsPerSec_;
        }
        if (frames > maxFramesPerPacket_) {
            frames = maxFramesPerPacket_;
        }
        return frames * frameBytes_;
    }

    // Decodes UAC1 (3-byte 10.14) or UAC2 HS (4-byte 16.16) feedback endpoint payload
    // with Linux snd-usb-audio +/-10% sanity bounds & /8 HS quirk correction.
    bool applyFeedbackPayload(const uint8_t* data, int len) {
        if (!data || (len != 3 && len != 4) || nominalFp1616_ == 0) return false;

        uint32_t candidateFp1616 = 0;
        if (len == 4) {
            uint32_t raw = static_cast<uint32_t>(data[0]) |
                           (static_cast<uint32_t>(data[1]) << 8) |
                           (static_cast<uint32_t>(data[2]) << 16) |
                           (static_cast<uint32_t>(data[3]) << 24);
            // HS 16.16 is samples per 125us microframe; scale by microframes per data interval
            candidateFp1616 = raw << intervalShift_;
        } else {
            // 3-byte 10.14 is samples per 1ms frame -> shift left by 2 for 16.16
            uint32_t raw1014 = static_cast<uint32_t>(data[0]) |
                               (static_cast<uint32_t>(data[1]) << 8) |
                               (static_cast<uint32_t>(data[2]) << 16);
            candidateFp1616 = (raw1014 << 2) << intervalShift_;
            if (isHighSpeed_) {
                // Convert per-1ms-frame 16.16 to per-125us-microframe if HS DAC sends 3-byte 10.14
                candidateFp1616 >>= 3;
            }
        }

        const uint32_t minValid = (nominalFp1616_ * 90u) / 100u;
        const uint32_t maxValid = (nominalFp1616_ * 110u) / 100u;

        // Quirk guard: some HS DACs report samples/ms in 4-byte 16.16 instead of samples/125us
        if (isHighSpeed_ && candidateFp1616 > maxValid) {
            uint32_t div8 = candidateFp1616 >> 3;
            if (div8 >= minValid && div8 <= maxValid) {
                candidateFp1616 = div8;
            }
        }

        if (candidateFp1616 < minValid || candidateFp1616 > maxValid) {
            return false;
        }

        activeFp1616_.store(candidateFp1616, std::memory_order_relaxed);
        uint32_t hz = static_cast<uint32_t>(
            (static_cast<uint64_t>(candidateFp1616) * intervalsPerSec_) >> 16
        );
        lastFeedbackHz_.store(hz, std::memory_order_relaxed);
        return true;
    }

    uint32_t intervalsPerSec() const { return intervalsPerSec_; }
    uint32_t nominalFp1616() const { return nominalFp1616_; }
    uint32_t lastFeedbackHz() const { return lastFeedbackHz_.load(std::memory_order_relaxed); }
    bool isHighSpeed() const { return isHighSpeed_; }

private:
    uint32_t sampleRate_ = 44100;
    uint32_t frameBytes_ = 4;
    uint32_t maxPacketBytes_ = 256;
    uint32_t maxFramesPerPacket_ = 64;
    bool useAsyncFeedback_ = false;
    bool isHighSpeed_ = true;
    uint32_t intervalShift_ = 0;
    uint32_t intervalsPerSec_ = 8000;

    uint64_t rationalAcc_ = 0;

    uint32_t nominalFp1616_ = 0;
    std::atomic<uint32_t> activeFp1616_{0};
    uint32_t fracAccFp1616_ = 0;
    std::atomic<uint32_t> lastFeedbackHz_{0};
};

struct StreamTelemetry {
    bool streaming = false;
    uint32_t sampleRate = 0;
    uint8_t bitResolution = 0;
    uac::PcmFormat pcmFormat = uac::PcmFormat::Unknown;
    uac::EndpointSyncType syncType = uac::EndpointSyncType::None;
    bool usingFeedback = false;
    uint32_t feedbackHz = 0;
    uint32_t packetsPerUrb = 0;
    uint32_t inFlightUrbs = 0;
    uint64_t packetsSubmitted = 0;
    uint64_t transferErrors = 0;
    uint64_t underruns = 0;
    size_t bufferedBytes = 0;
    size_t targetBufferBytes = 0;
    int isoThreadTid = 0;
    double maxCallbackGapMs = 0.0;
};

#ifndef HOST_UNIT_TEST

using IsoThreadStartedCallback = std::function<void(int tid)>;

/**
 * High-priority real-time asynchronous Isochronous OUT + Feedback URB pump.
 * Hot path is 100% lock-free and allocation-free.
 */
class UsbIsoStreamer {
public:
    static constexpr int kNumOutUrbs = 12;
    static constexpr int kNumFeedbackUrbs = 2;

    UsbIsoStreamer() = default;
    ~UsbIsoStreamer();

    UsbIsoStreamer(const UsbIsoStreamer&) = delete;
    UsbIsoStreamer& operator=(const UsbIsoStreamer&) = delete;

    void setThreadStartedCallback(IsoThreadStartedCallback cb) {
        onThreadStarted_ = std::move(cb);
    }

    int isoThreadTid() const { return isoThreadTid_.load(std::memory_order_acquire); }

    bool start(
        libusb_context* ctx,
        libusb_device_handle* handle,
        int usbSpeed,
        const uac::AudioStreamAltSetting& alt,
        uint32_t sampleRate,
        SpscRingBuffer* ringBuffer,
        size_t targetBufferBytes,
        std::string& outError
    );

    void stop();

    bool isRunning() const { return running_.load(std::memory_order_acquire); }
    StreamTelemetry telemetry() const;
    void updateTargetBufferBytes(size_t bytes) {
        targetBufferBytes_.store(bytes, std::memory_order_relaxed);
    }

private:
    struct TransferSlot {
        UsbIsoStreamer* owner = nullptr;
        libusb_transfer* transfer = nullptr;
        std::vector<uint8_t> buffer;
        bool isFeedback = false;
        std::atomic<bool> active{false};
    };

    void eventLoopThread();
    void fillAndSubmitOutSlot(TransferSlot* slot);
    void submitFeedbackSlot(TransferSlot* slot);

    static void onOutTransferComplete(libusb_transfer* xfer);
    static void onFeedbackTransferComplete(libusb_transfer* xfer);
    void handleOutComplete(TransferSlot* slot, libusb_transfer* xfer);
    void handleFeedbackComplete(TransferSlot* slot, libusb_transfer* xfer);

    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    uac::AudioStreamAltSetting activeAlt_{};
    uint32_t sampleRate_ = 0;
    uint32_t frameBytes_ = 4;
    int numIsoPacketsPerUrb_ = 16;
    bool useFeedback_ = false;

    SpscRingBuffer* ringBuffer_ = nullptr;
    IsoPacketPacer pacer_;

    std::vector<std::unique_ptr<TransferSlot>> outSlots_;
    std::vector<std::unique_ptr<TransferSlot>> fbSlots_;

    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<int> inFlightCount_{0};
    std::atomic<uint64_t> packetsSubmitted_{0};
    std::atomic<uint64_t> transferErrors_{0};
    std::atomic<uint64_t> isoPacketErrors_{0};
    std::atomic<uint64_t> isoUnderruns_{0};
    std::atomic<uint64_t> maxCallbackGapUs_{0};
    std::atomic<size_t> targetBufferBytes_{0};
    std::atomic<int> isoThreadTid_{0};
    uint64_t lastOutCallbackNs_{0};
    IsoThreadStartedCallback onThreadStarted_;

    std::thread eventThread_;
};

#endif // HOST_UNIT_TEST

} // namespace bitperfect
