#include "usb_iso_streamer.h"
#include "native_log.h"
#include "pcm_converter.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <libusb.h>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <unistd.h>

namespace bitperfect {

namespace {

int getCurrentKernelTid() {
#if defined(__ANDROID__)
    return static_cast<int>(gettid());
#elif defined(SYS_gettid)
    return static_cast<int>(syscall(SYS_gettid));
#else
    return 0;
#endif
}

void configureRealtimeAudioThread(int tid) {
#ifdef __ANDROID__
    pthread_setname_np(pthread_self(), "BP_USB_ISO");
#endif
    // Try SCHED_FIFO directly first (Root libsu chrt -f -p <tid> 50 will also elevate via callback)
    struct sched_param sp{};
    sp.sched_priority = 50;
    if (sched_setscheduler(0, SCHED_FIFO, &sp) == 0) {
        BP_LOGI("USB ISO thread (tid=%d) scheduling: SCHED_FIFO (prio=%d)", tid, sp.sched_priority);
        return;
    }

    // Standard Zero-Root urgent nice priority
    int targets[] = {-19, -16, -10};
    for (int niceVal : targets) {
        if (setpriority(PRIO_PROCESS, 0, niceVal) == 0) {
            BP_LOGI("USB ISO thread (tid=%d) scheduling: nice=%d (urgent audio priority)",
                    tid, niceVal);
            return;
        }
    }
    int actual = getpriority(PRIO_PROCESS, 0);
    BP_LOGW("USB ISO thread (tid=%d) scheduling: nice=%d", tid, actual);
}

} // namespace

UsbIsoStreamer::~UsbIsoStreamer() {
    stop();
}

bool UsbIsoStreamer::start(
    libusb_context* ctx,
    libusb_device_handle* handle,
    int usbSpeed,
    const uac::AudioStreamAltSetting& alt,
    uint32_t sampleRate,
    SpscRingBuffer* ringBuffer,
    size_t targetBufferBytes,
    bool conservativeXhciRing,
    std::string& outError
) {
    stop();

    if (!ctx || !handle || !ringBuffer) {
        outError = "Invalid arguments to UsbIsoStreamer::start";
        return false;
    }

    frameBytes_ = static_cast<uint32_t>(pcmFrameBytes(alt.pcmFormat, alt.channels));
    if (frameBytes_ == 0 || alt.maxPacketBytesPerInterval < frameBytes_) {
        outError = "Unsupported PCM format or invalid endpoint wMaxPacketSize";
        return false;
    }

    ctx_ = ctx;
    handle_ = handle;
    activeAlt_ = alt;
    sampleRate_ = sampleRate;
    ringBuffer_ = ringBuffer;
    targetBufferBytes_.store(targetBufferBytes, std::memory_order_relaxed);
    firstOutCbLogged_ = false;

    // Enable explicit async feedback ONLY when endpoint is Async + has explicit Feedback EP
    useFeedback_ = (alt.syncType == uac::EndpointSyncType::Asynchronous) &&
                   alt.hasFeedbackEndpoint &&
                   (alt.feedbackEndpointAddr != 0);

    pacer_.configure(
        sampleRate_,
        usbSpeed,
        alt.interval,
        frameBytes_,
        alt.maxPacketBytesPerInterval,
        useFeedback_
    );

    // Adaptive URB sizing:
    //  - Conservative single-segment xHCI ring (non-Qualcomm SoCs: XRING / Exynos / Dimensity / Tensor):
    //      * Low-latency mode (<= 85ms): 6 URBs x 16 pkts = 96 microframes (12ms DMA ring, < 255 TRBs)
    //      * Hi-Fi mode (> 85ms):        6 URBs x 32 pkts = 192 microframes (24ms DMA ring, < 255 TRBs)
    //      Both stay strictly inside ONE 256-TRB xHCI transfer ring segment (zero ring expansion) while
    //      giving 24ms USB wire headroom to absorb USB Hub / OEM scheduler spikes (e.g. 15ms gaps).
    //  - Qualcomm low-latency mode (target <= 85ms): 12 URBs x 16 pkts = 24ms DMA ring.
    //  - Qualcomm Hi-Fi mode (target > 85ms): 12 URBs x 32 pkts = 48ms DMA ring.
    const double targetMs = (sampleRate_ > 0 && frameBytes_ > 0)
        ? (1000.0 * static_cast<double>(targetBufferBytes)) /
          (static_cast<double>(sampleRate_) * static_cast<double>(frameBytes_))
        : 250.0;
    const bool lowLatencyMode = (targetMs <= 85.0);

    if (conservativeXhciRing) {
        activeOutUrbs_ = 6;
        if (pacer_.isHighSpeed()) {
            if (pacer_.intervalsPerSec() >= 8000u) {
                numIsoPacketsPerUrb_ = lowLatencyMode ? 16 : 32; // 12ms (96 TRBs) or 24ms (192 TRBs < 255)
            } else if (pacer_.intervalsPerSec() >= 4000u) {
                numIsoPacketsPerUrb_ = lowLatencyMode ? 8 : 16;
            } else {
                numIsoPacketsPerUrb_ = lowLatencyMode ? 4 : 8;
            }
        } else {
            numIsoPacketsPerUrb_ = lowLatencyMode ? 2 : 4; // 12ms or 24ms on Full-Speed
        }
    } else {
        activeOutUrbs_ = kNumOutUrbs;
        if (pacer_.isHighSpeed()) {
            if (pacer_.intervalsPerSec() >= 8000u) {
                numIsoPacketsPerUrb_ = lowLatencyMode ? 16 : 32; // 2.0ms or 4.0ms per URB
            } else if (pacer_.intervalsPerSec() >= 4000u) {
                numIsoPacketsPerUrb_ = lowLatencyMode ? 8 : 16;
            } else {
                numIsoPacketsPerUrb_ = lowLatencyMode ? 4 : 8;
            }
        } else {
            numIsoPacketsPerUrb_ = lowLatencyMode ? 2 : 4; // 2.0ms or 4.0ms per URB on Full-Speed
        }
    }

    packetsSubmitted_.store(0, std::memory_order_relaxed);
    transferErrors_.store(0, std::memory_order_relaxed);
    isoPacketErrors_.store(0, std::memory_order_relaxed);
    isoUnderruns_.store(0, std::memory_order_relaxed);
    maxCallbackGapUs_.store(0, std::memory_order_relaxed);
    lastOutCallbackNs_ = 0;
    inFlightCount_.store(0, std::memory_order_relaxed);
    stopRequested_.store(false, std::memory_order_release);
    running_.store(true, std::memory_order_release);

    BP_LOGI("Starting USB ISO streamer: rate=%uHz fmt=%s (%uB/frame) sync=%s fb=%s "
            "intervals=%u/s urbs=%d pkts/urb=%d maxPkt=%uB xhciSafe=%s",
            sampleRate_,
            uac::pcmFormatToString(alt.pcmFormat),
            frameBytes_,
            uac::syncTypeToString(alt.syncType),
            useFeedback_ ? "ON" : "OFF",
            pacer_.intervalsPerSec(),
            activeOutUrbs_,
            numIsoPacketsPerUrb_,
            alt.maxPacketBytesPerInterval,
            conservativeXhciRing ? "ON" : "OFF");

    eventThread_ = std::thread(&UsbIsoStreamer::eventLoopThread, this);
    return true;
}

void UsbIsoStreamer::stop() {
    stopRequested_.store(true, std::memory_order_release);
    if (eventThread_.joinable()) {
        eventThread_.join();
    }
    running_.store(false, std::memory_order_release);
}

void UsbIsoStreamer::eventLoopThread() {
    const int tid = getCurrentKernelTid();
    isoThreadTid_.store(tid, std::memory_order_release);
    configureRealtimeAudioThread(tid);

    // Notify Kotlin immediately so persistent libsu Root shell can apply `chrt -f -p 50 <tid>`
    if (tid > 0 && onThreadStarted_) {
        onThreadStarted_(tid);
    }

    // Allocate OUT transfer slots exclusively on the ISO thread
    outSlots_.clear();
    outSlots_.reserve(static_cast<size_t>(activeOutUrbs_));
    const size_t maxUrbBufBytes =
        static_cast<size_t>(numIsoPacketsPerUrb_) * activeAlt_.maxPacketBytesPerInterval;

    for (int i = 0; i < activeOutUrbs_; ++i) {
        auto slot = std::make_unique<TransferSlot>();
        slot->owner = this;
        slot->isFeedback = false;
        slot->buffer.assign(maxUrbBufBytes, 0);
        slot->transfer = libusb_alloc_transfer(numIsoPacketsPerUrb_);
        if (!slot->transfer) {
            BP_LOGE("libusb_alloc_transfer failed for OUT slot %d", i);
            continue;
        }
        libusb_fill_iso_transfer(
            slot->transfer,
            handle_,
            activeAlt_.dataEndpointAddr,
            slot->buffer.data(),
            static_cast<int>(slot->buffer.size()),
            numIsoPacketsPerUrb_,
            &UsbIsoStreamer::onOutTransferComplete,
            slot.get(),
            1000
        );
        outSlots_.push_back(std::move(slot));
    }

    // Allocate explicit Feedback IN slots if Async+Feedback DAC
    fbSlots_.clear();
    if (useFeedback_) {
        const uint16_t fbPktSize = std::max<uint16_t>(
            activeAlt_.feedbackMaxPacketSize,
            pacer_.isHighSpeed() ? 4u : 3u
        );
        fbSlots_.reserve(kNumFeedbackUrbs);
        for (int i = 0; i < kNumFeedbackUrbs; ++i) {
            auto slot = std::make_unique<TransferSlot>();
            slot->owner = this;
            slot->isFeedback = true;
            slot->buffer.assign(fbPktSize, 0);
            slot->transfer = libusb_alloc_transfer(1);
            if (!slot->transfer) continue;
            libusb_fill_iso_transfer(
                slot->transfer,
                handle_,
                activeAlt_.feedbackEndpointAddr,
                slot->buffer.data(),
                static_cast<int>(fbPktSize),
                1,
                &UsbIsoStreamer::onFeedbackTransferComplete,
                slot.get(),
                1000
            );
            slot->transfer->iso_packet_desc[0].length = fbPktSize;
            fbSlots_.push_back(std::move(slot));
        }
    }

    // Submit initial Feedback URBs first, then all OUT URBs
    if (onTrace_) onTrace_("ENTER iso_submit_initial_urbs");
    for (auto& slot : fbSlots_) {
        if (!stopRequested_.load(std::memory_order_acquire)) {
            submitFeedbackSlot(slot.get());
        }
    }
    for (auto& slot : outSlots_) {
        if (!stopRequested_.load(std::memory_order_acquire)) {
            fillAndSubmitOutSlot(slot.get());
        }
    }
    if (onTrace_) onTrace_("OK iso_submit_initial_urbs");

    // Real-time event loop (hot path: zero allocations, zero locks)
    bool cancelled = false;
    auto cancelDeadline = std::chrono::steady_clock::now();

    while (true) {
        const bool stopping = stopRequested_.load(std::memory_order_acquire);
        if (stopping && !cancelled) {
            cancelled = true;
            cancelDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
            for (auto& slot : outSlots_) {
                if (slot->active.load(std::memory_order_acquire) && slot->transfer) {
                    libusb_cancel_transfer(slot->transfer);
                }
            }
            for (auto& slot : fbSlots_) {
                if (slot->active.load(std::memory_order_acquire) && slot->transfer) {
                    libusb_cancel_transfer(slot->transfer);
                }
            }
        }

        if (cancelled) {
            if (inFlightCount_.load(std::memory_order_acquire) <= 0) {
                break;
            }
            if (std::chrono::steady_clock::now() > cancelDeadline) {
                BP_LOGW("Timed out waiting for %d in-flight USB URBs to drain",
                        inFlightCount_.load(std::memory_order_relaxed));
                break;
            }
        } else if (inFlightCount_.load(std::memory_order_acquire) <= 0) {
            // All URBs failed/terminated (e.g., USB unplug)
            BP_LOGW("All in-flight USB ISO URBs terminated unexpectedly");
            break;
        }

        struct timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = 10000; // 10ms poll slice for crisp stop responsiveness
        int rc = libusb_handle_events_timeout_completed(ctx_, &tv, nullptr);
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED && rc != LIBUSB_ERROR_TIMEOUT) {
            BP_LOGE("libusb_handle_events_timeout_completed error: %s", libusb_error_name(rc));
            break;
        }
    }

    // Free transfers ONLY if drained (avoid UAF on kernel-stuck transfers after unplug)
    if (inFlightCount_.load(std::memory_order_acquire) == 0) {
        for (auto& slot : outSlots_) {
            if (slot->transfer) {
                libusb_free_transfer(slot->transfer);
                slot->transfer = nullptr;
            }
        }
        for (auto& slot : fbSlots_) {
            if (slot->transfer) {
                libusb_free_transfer(slot->transfer);
                slot->transfer = nullptr;
            }
        }
    }
    outSlots_.clear();
    fbSlots_.clear();

    isoThreadTid_.store(0, std::memory_order_release);
    running_.store(false, std::memory_order_release);
    BP_LOGI("USB ISO streamer stopped (packets=%llu, xferErr=%llu, isoPktErr=%llu, underruns=%llu, maxCbGap=%.2fms)",
            static_cast<unsigned long long>(packetsSubmitted_.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(transferErrors_.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(isoPacketErrors_.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(isoUnderruns_.load(std::memory_order_relaxed)),
            static_cast<double>(maxCallbackGapUs_.load(std::memory_order_relaxed)) / 1000.0);
}

void UsbIsoStreamer::fillAndSubmitOutSlot(TransferSlot* slot) {
    libusb_transfer* xfer = slot->transfer;
    uint8_t* dst = slot->buffer.data();
    size_t totalBytes = 0;

    // Pack microframe packets contiguously (matches Linux usbfs USBDEVFS_SUBMITURB ISO layout)
    for (int p = 0; p < numIsoPacketsPerUrb_; ++p) {
        const uint32_t pktBytes = pacer_.nextPacketBytes();
        const size_t got = ringBuffer_->read(dst, pktBytes);
        if (got < pktBytes) {
            std::memset(dst + got, 0, pktBytes - got);
            isoUnderruns_.fetch_add(1, std::memory_order_relaxed);
        }
        xfer->iso_packet_desc[p].length = pktBytes;
        dst += pktBytes;
        totalBytes += pktBytes;
    }
    xfer->length = static_cast<int>(totalBytes);

    slot->active.store(true, std::memory_order_release);
    inFlightCount_.fetch_add(1, std::memory_order_acq_rel);

    int rc = libusb_submit_transfer(xfer);
    if (rc == LIBUSB_SUCCESS) {
        packetsSubmitted_.fetch_add(static_cast<uint64_t>(numIsoPacketsPerUrb_),
                                    std::memory_order_relaxed);
    } else {
        slot->active.store(false, std::memory_order_release);
        inFlightCount_.fetch_sub(1, std::memory_order_acq_rel);
        transferErrors_.fetch_add(1, std::memory_order_relaxed);
        if (rc == LIBUSB_ERROR_NO_DEVICE) {
            stopRequested_.store(true, std::memory_order_release);
        }
    }
}

void UsbIsoStreamer::submitFeedbackSlot(TransferSlot* slot) {
    libusb_transfer* xfer = slot->transfer;
    xfer->iso_packet_desc[0].length = static_cast<unsigned int>(slot->buffer.size());
    xfer->length = static_cast<int>(slot->buffer.size());

    slot->active.store(true, std::memory_order_release);
    inFlightCount_.fetch_add(1, std::memory_order_acq_rel);

    int rc = libusb_submit_transfer(xfer);
    if (rc != LIBUSB_SUCCESS) {
        slot->active.store(false, std::memory_order_release);
        inFlightCount_.fetch_sub(1, std::memory_order_acq_rel);
        if (rc == LIBUSB_ERROR_NO_DEVICE) {
            stopRequested_.store(true, std::memory_order_release);
        }
    }
}

void UsbIsoStreamer::onOutTransferComplete(libusb_transfer* xfer) {
    auto* slot = static_cast<TransferSlot*>(xfer->user_data);
    if (slot && slot->owner) {
        slot->owner->handleOutComplete(slot, xfer);
    }
}

void UsbIsoStreamer::onFeedbackTransferComplete(libusb_transfer* xfer) {
    auto* slot = static_cast<TransferSlot*>(xfer->user_data);
    if (slot && slot->owner) {
        slot->owner->handleFeedbackComplete(slot, xfer);
    }
}

void UsbIsoStreamer::handleOutComplete(TransferSlot* slot, libusb_transfer* xfer) {
    slot->active.store(false, std::memory_order_release);
    inFlightCount_.fetch_sub(1, std::memory_order_acq_rel);

    if (!firstOutCbLogged_) {
        firstOutCbLogged_ = true;
        if (onTrace_) onTrace_("OK first_iso_out_callback");
    }

    const uint64_t nowNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()
        ).count()
    );
    if (lastOutCallbackNs_ != 0 && nowNs > lastOutCallbackNs_) {
        const uint64_t gapUs = (nowNs - lastOutCallbackNs_) / 1000u;
        uint64_t prevMax = maxCallbackGapUs_.load(std::memory_order_relaxed);
        if (gapUs > prevMax) {
            maxCallbackGapUs_.store(gapUs, std::memory_order_relaxed);
        }
    }
    lastOutCallbackNs_ = nowNs;

    if (xfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        stopRequested_.store(true, std::memory_order_release);
        return;
    }
    if (xfer->status != LIBUSB_TRANSFER_COMPLETED &&
        xfer->status != LIBUSB_TRANSFER_CANCELLED) {
        transferErrors_.fetch_add(1, std::memory_order_relaxed);
    } else if (xfer->status == LIBUSB_TRANSFER_COMPLETED) {
        // Count per-microframe ISO packet failures (Linux usbfs reports -EXDEV here, not in xfer->status!)
        uint64_t pktErrs = 0;
        for (int p = 0; p < xfer->num_iso_packets; ++p) {
            if (xfer->iso_packet_desc[p].status != LIBUSB_TRANSFER_COMPLETED) {
                ++pktErrs;
            }
        }
        if (pktErrs > 0) {
            isoPacketErrors_.fetch_add(pktErrs, std::memory_order_relaxed);
        }
    }

    if (!stopRequested_.load(std::memory_order_acquire)) {
        fillAndSubmitOutSlot(slot);
    }
}

void UsbIsoStreamer::handleFeedbackComplete(TransferSlot* slot, libusb_transfer* xfer) {
    slot->active.store(false, std::memory_order_release);
    inFlightCount_.fetch_sub(1, std::memory_order_acq_rel);

    if (xfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        stopRequested_.store(true, std::memory_order_release);
        return;
    }

    if (xfer->status == LIBUSB_TRANSFER_COMPLETED &&
        xfer->num_iso_packets >= 1 &&
        xfer->iso_packet_desc[0].status == LIBUSB_TRANSFER_COMPLETED) {
        const int actualLen = static_cast<int>(xfer->iso_packet_desc[0].actual_length);
        if (actualLen == 3 || actualLen == 4) {
            pacer_.applyFeedbackPayload(slot->buffer.data(), actualLen);
        }
    }

    if (!stopRequested_.load(std::memory_order_acquire)) {
        submitFeedbackSlot(slot);
    }
}

StreamTelemetry UsbIsoStreamer::telemetry() const {
    StreamTelemetry t{};
    t.streaming = running_.load(std::memory_order_acquire);
    t.sampleRate = sampleRate_;
    t.bitResolution = activeAlt_.bitResolution;
    t.pcmFormat = activeAlt_.pcmFormat;
    t.syncType = activeAlt_.syncType;
    t.usingFeedback = useFeedback_;
    t.feedbackHz = pacer_.lastFeedbackHz();
    t.packetsPerUrb = static_cast<uint32_t>(numIsoPacketsPerUrb_);
    t.inFlightUrbs = static_cast<uint32_t>(std::max(0, inFlightCount_.load(std::memory_order_relaxed)));
    t.packetsSubmitted = packetsSubmitted_.load(std::memory_order_relaxed);
    t.transferErrors = transferErrors_.load(std::memory_order_relaxed) +
                       isoPacketErrors_.load(std::memory_order_relaxed);
    t.underruns = isoUnderruns_.load(std::memory_order_relaxed);
    t.bufferedBytes = ringBuffer_ ? ringBuffer_->availableRead() : 0;
    t.targetBufferBytes = targetBufferBytes_.load(std::memory_order_relaxed);
    t.isoThreadTid = isoThreadTid_.load(std::memory_order_acquire);
    t.maxCallbackGapMs = static_cast<double>(maxCallbackGapUs_.load(std::memory_order_relaxed)) / 1000.0;
    return t;
}

} // namespace bitperfect
