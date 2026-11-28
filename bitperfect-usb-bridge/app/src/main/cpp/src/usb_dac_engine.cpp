#include "usb_dac_engine.h"
#include "native_log.h"
#include "uac_constants.h"
#include "uac_parser.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fcntl.h>
#include <iomanip>
#include <libusb.h>
#include <set>
#include <sstream>
#include <thread>
#include <unistd.h>

namespace bitperfect {

namespace {

inline uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

inline uint32_t readLe24(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16);
}

bool altSupportsRate(const uac::AudioStreamAltSetting& alt, uint32_t sampleRate) {
    if (alt.continuousRate && sampleRate >= alt.minSampleRate && sampleRate <= alt.maxSampleRate) {
        return true;
    }
    return std::find(alt.sampleRates.begin(), alt.sampleRates.end(), sampleRate) !=
           alt.sampleRates.end();
}

} // namespace

UsbDacEngine::~UsbDacEngine() {
    close();
}

void UsbDacEngine::setTraceFilePath(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex_);
    traceFilePath_ = path;
}

void UsbDacEngine::writeTrace(const std::string& step, bool truncate) const {
    if (traceFilePath_.empty()) return;
    int flags = O_WRONLY | O_CREAT | (truncate ? O_TRUNC : O_APPEND);
    int fd = ::open(traceFilePath_.c_str(), flags, 0600);
    if (fd < 0) return;
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
    std::string line = std::to_string(ms) + " " + step + "\n";
    ssize_t ignored = ::write(fd, line.data(), line.size());
    (void)ignored;
    ::fsync(fd);
    ::close(fd);
}

bool UsbDacEngine::initLibusbContext(std::string& outError) {
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x0100010A)
    const struct libusb_init_option options[] = {
        { .option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY, .value = { .ival = 0 } }
    };
    int rc = libusb_init_context(&ctx_, options, 1);
    if (rc != LIBUSB_SUCCESS) {
        outError = std::string("libusb_init_context(NO_DEVICE_DISCOVERY) failed: ") +
                   libusb_error_name(rc);
        BP_LOGE("%s", outError.c_str());
        return false;
    }
#else
    int optRc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (optRc != LIBUSB_SUCCESS) {
        BP_LOGW("libusb_set_option(NO_DEVICE_DISCOVERY) returned %s", libusb_error_name(optRc));
    }
    int rc = libusb_init(&ctx_);
    if (rc != LIBUSB_SUCCESS) {
        outError = std::string("libusb_init failed: ") + libusb_error_name(rc);
        BP_LOGE("%s", outError.c_str());
        return false;
    }
#endif
    BP_LOGI("libusb initialized (NO_DEVICE_DISCOVERY enabled, version=%d.%d.%d)",
            libusb_get_version()->major,
            libusb_get_version()->minor,
            libusb_get_version()->micro);
    return true;
}

void UsbDacEngine::claimAudioInterfacesLocked() {
    if (!handle_) return;
    libusb_device* dev = libusb_get_device(handle_);
    if (!dev) return;

    libusb_config_descriptor* config = nullptr;
    int rc = libusb_get_active_config_descriptor(dev, &config);
    if (rc != LIBUSB_SUCCESS || !config) {
        rc = libusb_get_config_descriptor(dev, 0, &config);
    }
    if (rc != LIBUSB_SUCCESS || !config) {
        BP_LOGW("Could not enumerate interfaces for libusb_claim_interface: %s",
                libusb_error_name(rc));
        return;
    }

    std::set<int> controlIfaces;
    std::set<int> playbackStreamIfaces;
    std::set<int> captureStreamIfaces;

    int warmupIfNum = -1;
    int warmupAltNum = -1;
    uint8_t warmupEpAddr = 0;
    uint16_t warmupMaxPkt = 0;

    for (int i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& iface = config->interface[i];
        bool isControl = false;
        bool isPlaybackStream = false;
        bool isCaptureStream = false;
        int ifNum = -1;

        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            if (alt.bInterfaceClass != uac::USB_CLASS_AUDIO) continue;
            ifNum = alt.bInterfaceNumber;

            if (alt.bInterfaceSubClass == uac::SUBCLASS_AUDIOCONTROL) {
                isControl = true;
            } else if (alt.bInterfaceSubClass == uac::SUBCLASS_AUDIOSTREAMING) {
                for (int e = 0; e < alt.bNumEndpoints; ++e) {
                    const libusb_endpoint_descriptor& ep = alt.endpoint[e];
                    const bool isIso = (ep.bmAttributes & 0x03u) == LIBUSB_ENDPOINT_TRANSFER_TYPE_ISOCHRONOUS;
                    if (!isIso) continue;
                    const bool isOut = (ep.bEndpointAddress & 0x80u) == 0u;
                    if (isOut) {
                        isPlaybackStream = true;
                        if (warmupIfNum < 0 && alt.bAlternateSetting > 0 && ep.wMaxPacketSize > 0) {
                            warmupIfNum = alt.bInterfaceNumber;
                            warmupAltNum = alt.bAlternateSetting;
                            warmupEpAddr = ep.bEndpointAddress;
                            warmupMaxPkt = static_cast<uint16_t>(ep.wMaxPacketSize & 0x07FFu);
                        }
                    } else {
                        isCaptureStream = true;
                    }
                }
            }
        }

        if (ifNum >= 0) {
            if (isControl) controlIfaces.insert(ifNum);
            else if (isPlaybackStream) playbackStreamIfaces.insert(ifNum);
            else if (isCaptureStream) captureStreamIfaces.insert(ifNum);
        }
    }
    libusb_free_config_descriptor(config);

    if (!isQualcommPlatform_) {
        // Universal Offload-Safe Detach Sequence (Xiaomi XRING O1 / Exynos / MediaTek / Tensor / Generic ARM):
        //  1. Detach primary AudioControl IF#0 FIRST so snd-usb-audio / xring_usb_adsp_v1 / mtk-usb-offload
        //     cleanly executes usb_audio_disconnect() and exits ADSP offload mode atomically.
        //  2. Wait 50ms for kernel offload teardown to settle.
        //  3. Detach any still-attached sibling Capture/Playback interfaces (never leaving offload half-bound).
        //  4. Claim IF#0 & Playback IF#2 cleanly with ZERO Stage-1 ISO warmup and ZERO illegal clear_halt!
        for (int acIf : controlIfaces) {
            writeTrace("ENTER kernel_driver_active(AC IF#" + std::to_string(acIf) + ")");
            int active = libusb_kernel_driver_active(handle_, acIf);
            writeTrace("OK kernel_driver_active(AC IF#" + std::to_string(acIf) + ")=" + std::to_string(active));
            if (active == 1) {
                writeTrace("ENTER detach_kernel_driver(AC IF#" + std::to_string(acIf) + ")");
                int detachRc = libusb_detach_kernel_driver(handle_, acIf);
                writeTrace("OK detach_kernel_driver(AC IF#" + std::to_string(acIf) + ")=" + libusb_error_name(detachRc));
                BP_LOGI("[Universal-1] libusb_detach_kernel_driver(AudioControl IF#%d) -> %s",
                        acIf, libusb_error_name(detachRc));
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        // Verify sibling Capture/Mic interfaces were released by usb_audio_disconnect(); detach if still active
        for (int micIf : captureStreamIfaces) {
            int active = libusb_kernel_driver_active(handle_, micIf);
            BP_LOGI("[Universal-2] Capture IF#%d kernel_driver_active=%d (after IF#0 detach)", micIf, active);
            if (active == 1) {
                writeTrace("ENTER detach_kernel_driver(Mic IF#" + std::to_string(micIf) + ")");
                int detachRc = libusb_detach_kernel_driver(handle_, micIf);
                writeTrace("OK detach_kernel_driver(Mic IF#" + std::to_string(micIf) + ")=" + libusb_error_name(detachRc));
                BP_LOGI("[Universal-2] libusb_detach_kernel_driver(Mic IF#%d) -> %s",
                        micIf, libusb_error_name(detachRc));
                std::this_thread::sleep_for(std::chrono::milliseconds(15));
            }
        }

        // Verify & detach Playback interfaces if still kernel-bound
        for (int ifNum : playbackStreamIfaces) {
            int active = libusb_kernel_driver_active(handle_, ifNum);
            BP_LOGI("[Universal-2] Playback IF#%d kernel_driver_active=%d (after IF#0 detach)", ifNum, active);
            if (active == 1) {
                writeTrace("ENTER detach_kernel_driver(Playback IF#" + std::to_string(ifNum) + ")");
                int detachRc = libusb_detach_kernel_driver(handle_, ifNum);
                writeTrace("OK detach_kernel_driver(Playback IF#" + std::to_string(ifNum) + ")=" + libusb_error_name(detachRc));
                BP_LOGI("[Universal-2] libusb_detach_kernel_driver(Playback IF#%d) -> %s",
                        ifNum, libusb_error_name(detachRc));
                std::this_thread::sleep_for(std::chrono::milliseconds(15));
            }
        }

        // Claim AudioControl IF#0 then Playback IF#2 (pure interface claim — ZERO ISO traffic during inspect!)
        for (int acIf : controlIfaces) {
            writeTrace("ENTER claim_interface(AC IF#" + std::to_string(acIf) + ")");
            int claimRc = libusb_claim_interface(handle_, acIf);
            writeTrace("OK claim_interface(AC IF#" + std::to_string(acIf) + ")=" + libusb_error_name(claimRc));
            if (claimRc == LIBUSB_SUCCESS) {
                claimedInterfaces_.push_back(acIf);
                BP_LOGI("[Universal-3] libusb claimed AudioControl IF#%d successfully", acIf);
            } else {
                BP_LOGW("[Universal-3] libusb_claim_interface(AudioControl IF#%d) returned %s",
                        acIf, libusb_error_name(claimRc));
            }
        }

        if (!controlIfaces.empty() && !playbackStreamIfaces.empty()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(15));
        }

        for (int ifNum : playbackStreamIfaces) {
            writeTrace("ENTER claim_interface(Playback IF#" + std::to_string(ifNum) + ")");
            int claimRc = libusb_claim_interface(handle_, ifNum);
            writeTrace("OK claim_interface(Playback IF#" + std::to_string(ifNum) + ")=" + libusb_error_name(claimRc));
            if (claimRc == LIBUSB_SUCCESS) {
                claimedInterfaces_.push_back(ifNum);
                BP_LOGI("[Universal-3] libusb claimed Playback IF#%d successfully (跳过 Stage-1 ISO 预握手)", ifNum);
            } else {
                BP_LOGW("[Universal-3] libusb_claim_interface(Playback IF#%d) returned %s",
                        ifNum, libusb_error_name(claimRc));
            }
        }
        return;
    }

    // Qualcomm Snapdragon Stage 1: Detach & claim ONLY Playback AudioStreaming interface(s) (e.g. IF#2) FIRST!
    for (int ifNum : playbackStreamIfaces) {
        int active = libusb_kernel_driver_active(handle_, ifNum);
        if (active == 1) {
            writeTrace("ENTER qcom_detach(Playback IF#" + std::to_string(ifNum) + ")");
            int detachRc = libusb_detach_kernel_driver(handle_, ifNum);
            writeTrace("OK qcom_detach(Playback IF#" + std::to_string(ifNum) + ")=" + libusb_error_name(detachRc));
            BP_LOGI("[Stage-1] libusb_detach_kernel_driver(Playback IF#%d) -> %s",
                    ifNum, libusb_error_name(detachRc));
        }
        writeTrace("ENTER qcom_claim(Playback IF#" + std::to_string(ifNum) + ")");
        int claimRc = libusb_claim_interface(handle_, ifNum);
        writeTrace("OK qcom_claim(Playback IF#" + std::to_string(ifNum) + ")=" + libusb_error_name(claimRc));
        if (claimRc == LIBUSB_SUCCESS) {
            claimedInterfaces_.push_back(ifNum);
            BP_LOGI("[Stage-1] libusb claimed Playback IF#%d successfully", ifNum);
        } else {
            BP_LOGW("[Stage-1] libusb_claim_interface(Playback IF#%d) returned %s",
                    ifNum, libusb_error_name(claimRc));
        }
    }

    // Qualcomm-only Stage-1 silent ISO warm-up (WITHOUT illegal libusb_clear_halt on ISO endpoint!)
    if (ctx_ && warmupIfNum >= 0 && warmupAltNum > 0 && warmupEpAddr != 0 && warmupMaxPkt > 0) {
        int rcAlt = libusb_set_interface_alt_setting(handle_, warmupIfNum, warmupAltNum);
        if (rcAlt == LIBUSB_SUCCESS) {
            constexpr int kWarmupPkts = 32; // 4ms at High-Speed bInterval=1
            const int pktBytes = std::min<int>(static_cast<int>(warmupMaxPkt), 192);
            std::vector<uint8_t> zeroBuf(static_cast<size_t>(kWarmupPkts * pktBytes), 0u);
            libusb_transfer* xfer = libusb_alloc_transfer(kWarmupPkts);
            if (xfer) {
                int completed = 0;
                auto warmCb = [](libusb_transfer* t) {
                    if (t && t->user_data) {
                        *static_cast<int*>(t->user_data) = 1;
                    }
                };
                libusb_fill_iso_transfer(
                    xfer, handle_, warmupEpAddr,
                    zeroBuf.data(), static_cast<int>(zeroBuf.size()),
                    kWarmupPkts, warmCb, &completed, 100
                );
                libusb_set_iso_packet_lengths(xfer, static_cast<unsigned int>(pktBytes));
                if (libusb_submit_transfer(xfer) == LIBUSB_SUCCESS) {
                    struct timeval tv{0, 40000}; // 40ms max wait
                    for (int spin = 0; spin < 4 && !completed; ++spin) {
                        libusb_handle_events_timeout_completed(ctx_, &tv, &completed);
                    }
                    if (!completed) {
                        libusb_cancel_transfer(xfer);
                        struct timeval tvCancel{0, 15000};
                        libusb_handle_events_timeout_completed(ctx_, &tvCancel, &completed);
                    }
                }
                libusb_free_transfer(xfer);
            }
            libusb_set_interface_alt_setting(handle_, warmupIfNum, 0);
            BP_LOGI("[Stage-1] IF#%d Alt#%d 静音 ISO 预握手完成 (4ms 全零帧 -> 归位 Alt#0)",
                    warmupIfNum, warmupAltNum);
        }
    }

    if (!playbackStreamIfaces.empty() && !controlIfaces.empty()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(15));
    }

    // Qualcomm Stage 2: Detach & claim AudioControl IF#0 ONLY
    for (int acIf : controlIfaces) {
        int active = libusb_kernel_driver_active(handle_, acIf);
        if (active == 1) {
            int detachRc = libusb_detach_kernel_driver(handle_, acIf);
            BP_LOGI("[Stage-2] libusb_detach_kernel_driver(AudioControl IF#%d) -> %s",
                    acIf, libusb_error_name(detachRc));
        }
        int claimRc = libusb_claim_interface(handle_, acIf);
        if (claimRc == LIBUSB_SUCCESS) {
            claimedInterfaces_.push_back(acIf);
            BP_LOGI("[Stage-2] libusb claimed AudioControl IF#%d successfully", acIf);
        } else {
            BP_LOGW("[Stage-2] libusb_claim_interface(AudioControl IF#%d) returned %s",
                    acIf, libusb_error_name(claimRc));
        }
    }

    for (int micIf : captureStreamIfaces) {
        BP_LOGI("[Stage-2] 跳过麦克风接口 IF#%d (高通模式保持不剥离，隔离 ADC ClockSource)", micIf);
    }
}

int UsbDacEngine::controlTransferAcLocked(
    uint8_t bmRequestType,
    uint8_t bRequest,
    uint16_t wValue,
    uint16_t wIndex,
    uint8_t* data,
    uint16_t wLength,
    unsigned int timeoutMs
) {
    if (!handle_) return LIBUSB_ERROR_NO_DEVICE;
    return libusb_control_transfer(handle_, bmRequestType, bRequest, wValue, wIndex, data, wLength, timeoutMs);
}

bool UsbDacEngine::openAndInspect(
    int fd,
    uint16_t fallbackVid,
    uint16_t fallbackPid,
    const std::string& fallbackName,
    double initialVolumeDb,
    bool isQualcommPlatform,
    std::string& outReport,
    std::string& outProfileJson,
    std::string& outError
) {
    std::lock_guard<std::mutex> lock(mutex_);
    closeLocked(false);

    isQualcommPlatform_ = isQualcommPlatform;
    isoStreamer_.setTraceCallback([this](const char* msg) {
        writeTrace(msg);
    });
    writeTrace(std::string("START openAndInspect fd=") + std::to_string(fd) +
               " isQcom=" + (isQualcommPlatform_ ? "1" : "0"), true);

    if (fd < 0) {
        outError = "Invalid USB file descriptor (< 0)";
        BP_LOGE("%s", outError.c_str());
        writeTrace("FAIL invalid_fd");
        return false;
    }

    if (!initLibusbContext(outError)) {
        writeTrace("FAIL initLibusbContext");
        return false;
    }

    BP_LOGI("Wrapping Android USB fd=%d via libusb_wrap_sys_device (strategy=%s)...",
            fd, isQualcommPlatform_ ? "Qualcomm-TwoStage" : "Universal-OffloadSafe");
    writeTrace("ENTER libusb_wrap_sys_device");
    int rc = libusb_wrap_sys_device(ctx_, static_cast<intptr_t>(fd), &handle_);
    if (rc != LIBUSB_SUCCESS || !handle_) {
        outError = std::string("libusb_wrap_sys_device(fd=") + std::to_string(fd) +
                   ") failed: " + libusb_error_name(rc);
        BP_LOGE("%s", outError.c_str());
        writeTrace("FAIL libusb_wrap_sys_device");
        closeLocked();
        return false;
    }
    writeTrace("OK libusb_wrap_sys_device");
    BP_LOGI("libusb_wrap_sys_device succeeded (handle=%p)", static_cast<void*>(handle_));

    claimAudioInterfacesLocked();

    writeTrace("ENTER UacParser::parseFromLibusbDevice");
    if (!uac::UacParser::parseFromLibusbDevice(
            handle_, fallbackVid, fallbackPid, fallbackName, profile_, outError)) {
        BP_LOGE("UAC descriptor parsing failed: %s", outError.c_str());
        writeTrace("FAIL UacParser");
        closeLocked();
        return false;
    }
    writeTrace("OK UacParser::parseFromLibusbDevice");

    isOpen_ = true;
    outReport = uac::UacParser::formatDiagnosticReport(profile_);
    outProfileJson = uac::UacParser::toJson(profile_);

    std::istringstream iss(outReport);
    std::string line;
    while (std::getline(iss, line)) {
        BP_LOGI("%s", line.c_str());
    }

    // On Qualcomm, ensure claimed AudioStreaming interfaces start in Alt#0.
    // On non-Qualcomm SoCs (XRING/Exynos/MTK), freshly detached AS interfaces are already idle in Alt#0;
    // avoid redundant SET_INTERFACE during pure descriptor inspection.
    if (isQualcommPlatform_) {
        resetAllStreamingInterfacesToAlt0Locked();
    }

    // Initialize DAC hardware volume (uses saved user dB on rate-switch so volume never jumps to 0 dB!)
    if (profile_.hasPlaybackFeatureUnit) {
        writeTrace("ENTER init_hardware_volume");
        unmuteFeatureUnitLocked();
        if (profile_.playbackFeatureUnit.masterVolume || profile_.playbackFeatureUnit.channelVolume) {
            std::string volErr;
            if (!setHardwareVolumeDbLocked(initialVolumeDb, volErr)) {
                BP_LOGW("Initial %.2f dB hardware volume init failed: %s", initialVolumeDb, volErr.c_str());
            } else {
                currentVolumeDb_.store(initialVolumeDb, std::memory_order_relaxed);
            }
        }
        writeTrace("OK init_hardware_volume");
    }

    writeTrace("DONE openAndInspect");
    return true;
}

void UsbDacEngine::resetAllStreamingInterfacesToAlt0Locked() {
    if (!handle_) return;
    std::set<int> streamingIfs;
    // Strictly reset ONLY AudioStreaming interfaces actually claimed by libusb (e.g. IF#2),
    // never touching unclaimed Capture/Mic IF#1!
    for (int ifNum : claimedInterfaces_) {
        if (ifNum >= 0 && ifNum != profile_.topology.controlInterfaceNumber) {
            streamingIfs.insert(ifNum);
        }
    }
    for (int ifNum : streamingIfs) {
        int rc = libusb_set_interface_alt_setting(handle_, ifNum, 0);
        if (rc == LIBUSB_SUCCESS) {
            BP_LOGI("Set AudioStreaming IF#%d -> Alt#0 (zero-bandwidth idle)", ifNum);
        } else {
            BP_LOGW("set_interface_alt_setting(IF#%d, Alt#0) -> %s", ifNum, libusb_error_name(rc));
        }
    }
}

void UsbDacEngine::unmuteFeatureUnitLocked() {
    if (!handle_ || !profile_.hasPlaybackFeatureUnit) return;
    const auto& fu = profile_.playbackFeatureUnit;
    const int acIf = profile_.topology.controlInterfaceNumber;
    if (acIf < 0) return;

    const uint8_t reqCode = (profile_.uacVersion == uac::UacVersion::Uac2)
                            ? uac::UAC2_REQ_CUR : uac::UAC1_SET_CUR;
    uint8_t unmuteVal = 0;

    if (fu.masterMute) {
        uint16_t wValue = static_cast<uint16_t>((uac::FU_MUTE_CONTROL << 8) | 0u);
        uint16_t wIndex = static_cast<uint16_t>((static_cast<uint16_t>(fu.id) << 8) | (acIf & 0xFF));
        int rc = controlTransferAcLocked(
            uac::REQ_TYPE_CLASS_IF_OUT, reqCode, wValue, wIndex, &unmuteVal, 1, 500
        );
        BP_LOGI("Unmute FU#%u Master(ch=0) -> %s", fu.id, rc == 1 ? "OK" : libusb_error_name(rc));
    } else if (fu.channelMute) {
        uint8_t chCount = std::max<uint8_t>(2u, fu.numLogicalChannels);
        for (uint8_t ch = 1; ch <= chCount; ++ch) {
            uint16_t wValue = static_cast<uint16_t>((uac::FU_MUTE_CONTROL << 8) | ch);
            uint16_t wIndex = static_cast<uint16_t>((static_cast<uint16_t>(fu.id) << 8) | (acIf & 0xFF));
            int rc = controlTransferAcLocked(
                uac::REQ_TYPE_CLASS_IF_OUT, reqCode, wValue, wIndex, &unmuteVal, 1, 500
            );
            BP_LOGI("Unmute FU#%u ch=%u -> %s", fu.id, ch, rc == 1 ? "OK" : libusb_error_name(rc));
        }
    }
}

bool UsbDacEngine::setHardwareVolumeDbLocked(double db, std::string& outError) {
    if (!handle_ || !isOpen_) {
        outError = "USB DAC not open";
        return false;
    }
    if (!profile_.hasPlaybackFeatureUnit) {
        outError = "DAC has no playback FeatureUnit";
        return false;
    }

    const auto& fu = profile_.playbackFeatureUnit;
    if (!fu.masterVolume && !fu.channelVolume) {
        outError = "FeatureUnit does not support volume control";
        return false;
    }
    const int acIf = profile_.topology.controlInterfaceNumber;
    if (acIf < 0) {
        outError = "Invalid AudioControl interface number";
        return false;
    }

    int32_t rawQ88 = static_cast<int32_t>(std::lrint(db * 256.0));
    if (fu.volumeRangeQueried) {
        rawQ88 = std::clamp<int32_t>(rawQ88, fu.volumeMinQ88, fu.volumeMaxQ88);
        if (fu.volumeResQ88 > 0) {
            int32_t offset = rawQ88 - fu.volumeMinQ88;
            int32_t steps = static_cast<int32_t>(std::lrint(
                static_cast<double>(offset) / static_cast<double>(fu.volumeResQ88)
            ));
            rawQ88 = fu.volumeMinQ88 + steps * fu.volumeResQ88;
            rawQ88 = std::clamp<int32_t>(rawQ88, fu.volumeMinQ88, fu.volumeMaxQ88);
        }
    } else {
        rawQ88 = std::clamp<int32_t>(rawQ88, -127 * 256, 0);
    }

    const int16_t q88 = static_cast<int16_t>(rawQ88);
    uint8_t payload[2] = {
        static_cast<uint8_t>(static_cast<uint16_t>(q88) & 0xFFu),
        static_cast<uint8_t>((static_cast<uint16_t>(q88) >> 8) & 0xFFu)
    };

    const uint8_t reqCode = (profile_.uacVersion == uac::UacVersion::Uac2)
                            ? uac::UAC2_REQ_CUR : uac::UAC1_SET_CUR;
    const uint16_t wIndex = static_cast<uint16_t>(
        (static_cast<uint16_t>(fu.id) << 8) | (static_cast<uint16_t>(acIf) & 0xFFu)
    );

    // Always record target hardware gain first so configureHardwareLocked() can re-enforce it
    // after SET_INTERFACE(Alt#X) even if an early SET_CUR returns transient LIBUSB_ERROR_PIPE.
    currentVolumeDb_.store(q88 / 256.0, std::memory_order_relaxed);

    auto writeVolumeWithRetry = [&](uint8_t channel) -> int {
        const uint16_t wValue = static_cast<uint16_t>((uac::FU_VOLUME_CONTROL << 8) | channel);
        int rc = LIBUSB_ERROR_OTHER;
        for (int attempt = 0; attempt < 3; ++attempt) {
            rc = controlTransferAcLocked(
                uac::REQ_TYPE_CLASS_IF_OUT, reqCode, wValue, wIndex, payload, 2, 500
            );
            if (rc == 2) return rc;
            if (attempt < 2) {
                std::this_thread::sleep_for(std::chrono::milliseconds(15));
            }
        }
        return rc;
    };

    bool anyOk = false;
    if (fu.masterVolume) {
        int rc = writeVolumeWithRetry(0u);
        if (rc == 2) {
            anyOk = true;
            BP_LOGI("HW Volume FU#%u Master(ch=0) set to %.2f dB (Q8.8=%d)",
                    fu.id, q88 / 256.0, q88);
        } else {
            outError = std::string("Master volume SET_CUR failed: ") + libusb_error_name(rc);
        }
    } else if (fu.channelVolume) {
        const uint8_t chCount = std::max<uint8_t>(2u, fu.numLogicalChannels);
        int okChannels = 0;
        for (uint8_t ch = 1; ch <= chCount; ++ch) {
            int rc = writeVolumeWithRetry(ch);
            if (rc == 2) {
                ++okChannels;
            } else {
                BP_LOGW("HW Volume FU#%u ch=%u SET_CUR failed after 3 tries: %s",
                        fu.id, ch, libusb_error_name(rc));
            }
        }
        anyOk = (okChannels > 0);
        if (okChannels == chCount) {
            BP_LOGI("HW Volume FU#%u Per-Channel(ch=1..%u) set to %.2f dB (Q8.8=%d)",
                    fu.id, chCount, q88 / 256.0, q88);
        } else if (okChannels > 0) {
            BP_LOGW("HW Volume FU#%u Per-Channel PARTIAL (%d/%u ok) at %.2f dB (Q8.8=%d)",
                    fu.id, okChannels, chCount, q88 / 256.0, q88);
        } else {
            outError = "Per-channel volume SET_CUR failed for all channels";
        }
    }

    return anyOk;
}

bool UsbDacEngine::setHardwareVolumeDb(double db, std::string& outError) {
    std::lock_guard<std::mutex> lock(mutex_);
    return setHardwareVolumeDbLocked(db, outError);
}

void UsbDacEngine::setBufferDurationMs(int bufferMs) {
    int clamped = std::clamp(bufferMs, 60, 600);
    bufferDurationMs_.store(clamped, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (hardwareConfigured_ && activeSampleRate_ > 0) {
            size_t fb = pcmFrameBytes(activeAlt_.pcmFormat, activeAlt_.channels);
            size_t target = computeTargetBufferBytes(activeSampleRate_, fb);
            captureTargetBytes_.store(target, std::memory_order_relaxed);
            isoStreamer_.updateTargetBufferBytes(target);
        }
    }
    BP_LOGI("SPSC RingBuffer target duration set to %d ms", clamped);
}

size_t UsbDacEngine::computeTargetBufferBytes(uint32_t sampleRate, size_t frameBytes) const {
    if (sampleRate == 0 || frameBytes == 0) return 65536u;
    int ms = bufferDurationMs_.load(std::memory_order_relaxed);
    size_t frames = (static_cast<size_t>(sampleRate) * static_cast<size_t>(ms)) / 1000u;
    size_t bytes = frames * frameBytes;
    size_t maxSafe = (ringBuffer_.capacity() * 3u) / 4u;
    bytes = std::min(bytes, maxSafe);
    bytes = (bytes / frameBytes) * frameBytes;
    return std::max(bytes, frameBytes * 256u);
}

const uac::AudioStreamAltSetting* UsbDacEngine::selectPlaybackAltLocked(
    uint32_t sampleRate,
    int preferredBitDepth
) const {
    const uac::AudioStreamAltSetting* best = nullptr;
    int bestScore = -1;

    for (const auto& alt : profile_.playbackAltSettings) {
        if (!alt.isPcm || alt.pcmFormat == uac::PcmFormat::Unknown) continue;
        if (!altSupportsRate(alt, sampleRate)) continue;

        int score = 0;
        if (alt.channels == 2) score += 1000;
        if (preferredBitDepth > 0 && alt.bitResolution == preferredBitDepth) {
            score += 500;
        } else if (preferredBitDepth > 0 && alt.bitResolution > preferredBitDepth) {
            // Prefer minimal lossless container >= requested bit depth
            score += 300 - (alt.bitResolution - preferredBitDepth);
        } else {
            score += alt.bitResolution * 4;
        }
        if (alt.pcmFormat == uac::PcmFormat::S24_3LE && preferredBitDepth == 24) {
            score += 20;
        }

        if (score > bestScore) {
            bestScore = score;
            best = &alt;
        }
    }
    return best;
}

bool UsbDacEngine::setUac2ClockRateLocked(
    const uac::AudioStreamAltSetting& alt,
    uint32_t sampleRate,
    uint32_t& outVerifiedRate,
    std::string& outError
) {
    const int acIf = profile_.topology.controlInterfaceNumber;
    if (acIf < 0) {
        outError = "Missing AudioControl interface for UAC2 clock switch";
        return false;
    }

    uint8_t clockId = alt.primaryClockSourceId;
    uint8_t routeSelectorId = 0;
    uint8_t routeSelectorPin = 0;

    for (const auto& route : alt.rateClockRoutes) {
        if (route.sampleRate == sampleRate) {
            clockId = route.clockSourceId;
            routeSelectorId = route.clockSelectorId;
            routeSelectorPin = route.clockSelectorPin;
            break;
        }
    }
    if (clockId == 0) {
        outError = "No UAC2 ClockSource Entity ID resolved for playback stream";
        return false;
    }

    // Step 1: Route ALL Clock Selector Units (0x0B) whose input pins contain PrimaryCS (e.g. ID=30)
    // and verify each via GET_CUR readback.
    std::set<uint8_t> configuredSelectors;
    auto selectClockPin = [&](uint8_t selId, uint8_t pin1Based) {
        if (selId == 0 || pin1Based == 0 || !configuredSelectors.insert(selId).second) return;
        uint8_t pinByte = pin1Based;
        const uint16_t wVal = static_cast<uint16_t>((uac::UAC2_CX_CLOCK_SELECTOR_CTRL << 8) | 0u);
        const uint16_t wIdx = static_cast<uint16_t>((static_cast<uint16_t>(selId) << 8) | (acIf & 0xFF));
        int rcSet = controlTransferAcLocked(
            uac::REQ_TYPE_CLASS_IF_OUT, uac::UAC2_REQ_CUR, wVal, wIdx, &pinByte, 1, 1000
        );
        uint8_t readPin = 0;
        int rcGet = controlTransferAcLocked(
            uac::REQ_TYPE_CLASS_IF_IN, uac::UAC2_REQ_CUR, wVal, wIdx, &readPin, 1, 500
        );
        BP_LOGI("UAC2 ClockSelector #%u -> pin=%u (target CS#%u) | SET_CUR=%s, GET_CUR=%s (readbackPin=%u)",
                selId, pin1Based, clockId,
                rcSet == 1 ? "OK" : libusb_error_name(rcSet),
                rcGet == 1 ? "OK" : libusb_error_name(rcGet),
                readPin);
    };

    if (routeSelectorId != 0 && routeSelectorPin != 0) {
        selectClockPin(routeSelectorId, routeSelectorPin);
    }
    for (const auto& sel : profile_.topology.clockSelectors) {
        for (size_t idx = 0; idx < sel.sourceIds.size(); ++idx) {
            if (sel.sourceIds[idx] == clockId) {
                selectClockPin(sel.id, static_cast<uint8_t>(idx + 1));
                break;
            }
        }
    }

    // Step 2: Program Primary ClockSource (e.g. CS#30) to target sample rate + verify GET_CUR & CLOCK_VALID == 1
    uint8_t ratePayload[4] = {
        static_cast<uint8_t>(sampleRate & 0xFFu),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFFu),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFFu),
        static_cast<uint8_t>((sampleRate >> 24) & 0xFFu)
    };
    const uint16_t wValue = static_cast<uint16_t>(uac::UAC2_CS_SAM_FREQ_CONTROL << 8);
    const uint16_t wIndex = static_cast<uint16_t>((static_cast<uint16_t>(clockId) << 8) | (acIf & 0xFF));

    int rc = controlTransferAcLocked(
        uac::REQ_TYPE_CLASS_IF_OUT, uac::UAC2_REQ_CUR, wValue, wIndex, ratePayload, 4, 1000
    );
    if (rc != 4) {
        outError = std::string("UAC2 ClockSource #") + std::to_string(clockId) +
                   " SET_CUR(" + std::to_string(sampleRate) + "Hz) failed: " + libusb_error_name(rc);
        return false;
    }

    std::this_thread::sleep_for(std::chrono::milliseconds(5));

    uint8_t verifyBuf[4] = {0, 0, 0, 0};
    int rcGet = controlTransferAcLocked(
        uac::REQ_TYPE_CLASS_IF_IN, uac::UAC2_REQ_CUR, wValue, wIndex, verifyBuf, 4, 1000
    );
    if (rcGet == 4) {
        outVerifiedRate = readLe32(verifyBuf);
        BP_LOGI("UAC2 ClockSource #%u locked: requested=%u Hz, readback=%u Hz",
                clockId, sampleRate, outVerifiedRate);
    } else {
        outVerifiedRate = sampleRate;
        BP_LOGW("UAC2 ClockSource #%u GET_CUR verify returned %s (assuming locked)",
                clockId, libusb_error_name(rcGet));
    }

    uint8_t validByte = 0;
    const uint16_t wValidVal = static_cast<uint16_t>(uac::UAC2_CS_CLOCK_VALID_CONTROL << 8);
    for (int attempt = 0; attempt < 5; ++attempt) {
        int rcValid = controlTransferAcLocked(
            uac::REQ_TYPE_CLASS_IF_IN, uac::UAC2_REQ_CUR, wValidVal, wIndex, &validByte, 1, 300
        );
        if (rcValid == 1) {
            if (validByte == 1) {
                BP_LOGI("UAC2 ClockSource #%u CLOCK_VALID=1 (PLL locked, attempt=%d)",
                        clockId, attempt + 1);
                break;
            }
            if (attempt < 4) {
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            } else {
                BP_LOGW("UAC2 ClockSource #%u CLOCK_VALID=%u after retries", clockId, validByte);
            }
        } else {
            break;
        }
    }

    // Note: Do NOT touch secondary ADC/Mic ClockSources (e.g. CS#27 for IF#1 Mic on Quark 2),
    // as IF#1 is intentionally left untouched on the kernel driver.
    return true;
}

bool UsbDacEngine::setUac1ClockRateLocked(
    const uac::AudioStreamAltSetting& alt,
    uint32_t sampleRate,
    uint32_t& outVerifiedRate,
    std::string& outError
) {
    if (!alt.uac1SamplingFreqControl) {
        BP_LOGI("UAC1 EP 0x%02X has fixed sample rate, skipping SET_CUR", alt.dataEndpointAddr);
        outVerifiedRate = sampleRate;
        return true;
    }

    uint8_t rate3[3] = {
        static_cast<uint8_t>(sampleRate & 0xFFu),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFFu),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFFu)
    };
    const uint16_t wValue = static_cast<uint16_t>(uac::UAC1_EP_CS_SAMPLING_FREQ << 8);
    const uint16_t wIndex = alt.dataEndpointAddr;

    int rc = libusb_control_transfer(
        handle_, uac::REQ_TYPE_CLASS_EP_OUT, uac::UAC1_SET_CUR, wValue, wIndex, rate3, 3, 1000
    );
    if (rc != 3) {
        outError = std::string("UAC1 EP SET_CUR(") + std::to_string(sampleRate) +
                   "Hz) failed: " + libusb_error_name(rc);
        return false;
    }

    uint8_t verify3[3] = {0, 0, 0};
    int rcGet = libusb_control_transfer(
        handle_, uac::REQ_TYPE_CLASS_EP_IN, uac::UAC1_GET_CUR, wValue, wIndex, verify3, 3, 500
    );
    outVerifiedRate = (rcGet == 3) ? readLe24(verify3) : sampleRate;
    BP_LOGI("UAC1 EP 0x%02X locked: requested=%u Hz, readback=%u Hz",
            alt.dataEndpointAddr, sampleRate, outVerifiedRate);
    return true;
}

bool UsbDacEngine::configureHardwareLocked(
    const uac::AudioStreamAltSetting& alt,
    uint32_t sampleRate,
    std::string& outError
) {
    BP_LOGI("Configuring DAC hardware: IF#%d Alt#%d (%s, %uch) @ %u Hz "
            "(USB 2.0 spec-strict UAC2 sequence: AS Alt#0 -> ClockSelector/ClockSource -> Alt#%d, zero clear_halt)",
            alt.interfaceNumber, alt.altSetting,
            uac::pcmFormatToString(alt.pcmFormat), alt.channels, sampleRate, alt.altSetting);

    writeTrace("ENTER configureHardware rate=" + std::to_string(sampleRate) +
               " IF#" + std::to_string(alt.interfaceNumber) + " Alt#" + std::to_string(alt.altSetting));

    // Step 1: Ensure AudioStreaming interface is in Alt#0 (zero-bandwidth idle) before programming UAC2 clock
    writeTrace("ENTER resetAllStreamingInterfacesToAlt0");
    resetAllStreamingInterfacesToAlt0Locked();
    writeTrace("OK resetAllStreamingInterfacesToAlt0");
    std::this_thread::sleep_for(std::chrono::milliseconds(5));

    uint32_t verifiedRate = 0;
    if (alt.version == uac::UacVersion::Uac2) {
        // Step 2 (strictly while in Alt#0!): Lock Clock Selector(s) + ClockSource
        writeTrace("ENTER setUac2ClockRate");
        if (!setUac2ClockRateLocked(alt, sampleRate, verifiedRate, outError)) {
            writeTrace("FAIL setUac2ClockRate");
            return false;
        }
        writeTrace("OK setUac2ClockRate verified=" + std::to_string(verifiedRate));

        // Note: NEVER call libusb_clear_halt() on Isochronous endpoints (USB 2.0 §5.6.4 forbids
        // HALT on ISO endpoints; calling xhci_endpoint_reset while in Alt#0 deadlocks custom xHCI drivers!).

        // Step 3: Activate Playback AltSetting AFTER clocks are locked so DAC latches
        // its internal digital oversampling filter & MCLK/BCLK/LRCK dividers at the exact target rate!
        writeTrace("ENTER set_interface_alt_setting(IF#" + std::to_string(alt.interfaceNumber) +
                   ", Alt#" + std::to_string(alt.altSetting) + ")");
        int rcAlt = libusb_set_interface_alt_setting(handle_, alt.interfaceNumber, alt.altSetting);
        if (rcAlt != LIBUSB_SUCCESS) {
            outError = std::string("libusb_set_interface_alt_setting(IF#") +
                       std::to_string(alt.interfaceNumber) + ", Alt#" +
                       std::to_string(alt.altSetting) + ") failed: " + libusb_error_name(rcAlt);
            BP_LOGE("%s", outError.c_str());
            writeTrace("FAIL set_interface_alt_setting");
            return false;
        }
        writeTrace("OK set_interface_alt_setting");
        BP_LOGI("Activated AudioStreaming IF#%d Alt#%d (%s) AFTER clock lock @ %u Hz",
                alt.interfaceNumber, alt.altSetting, uac::pcmFormatToString(alt.pcmFormat), verifiedRate);
    } else {
        // UAC 1.0 sets sampling frequency on the active streaming endpoint
        writeTrace("ENTER uac1_set_interface_alt_setting");
        int rcAlt = libusb_set_interface_alt_setting(handle_, alt.interfaceNumber, alt.altSetting);
        if (rcAlt != LIBUSB_SUCCESS) {
            outError = std::string("libusb_set_interface_alt_setting(IF#") +
                       std::to_string(alt.interfaceNumber) + ", Alt#" +
                       std::to_string(alt.altSetting) + ") failed: " + libusb_error_name(rcAlt);
            BP_LOGE("%s", outError.c_str());
            writeTrace("FAIL uac1_set_interface_alt_setting");
            return false;
        }
        writeTrace("OK uac1_set_interface_alt_setting");
        BP_LOGI("Activated AudioStreaming IF#%d Alt#%d (%s)",
                alt.interfaceNumber, alt.altSetting, uac::pcmFormatToString(alt.pcmFormat));
        if (!setUac1ClockRateLocked(alt, sampleRate, verifiedRate, outError)) {
            writeTrace("FAIL setUac1ClockRate");
            return false;
        }
    }

    // Step 4: Allow DAC PLL, digital filter & unmute relay to settle cleanly before submitting ISO URBs
    std::this_thread::sleep_for(std::chrono::milliseconds(15));

    // Step 5: Re-enforce hardware volume AFTER SET_INTERFACE(Alt#X) so DAC chips that reset
    // internal FeatureUnit gain to 0.0 dB on rate/bit-depth switches (or transiently returned PIPE
    // before AltSetting activation) NEVER blast the user's ears!
    if (profile_.hasPlaybackFeatureUnit &&
        (profile_.playbackFeatureUnit.masterVolume || profile_.playbackFeatureUnit.channelVolume)) {
        const double targetVolDb = currentVolumeDb_.load(std::memory_order_relaxed);
        std::string volErr;
        if (!setHardwareVolumeDbLocked(targetVolDb, volErr)) {
            BP_LOGW("Post-AltSetting HW volume re-apply (%.2f dB) failed: %s", targetVolDb, volErr.c_str());
        }
    }

    activeAlt_ = alt;
    activeSampleRate_ = sampleRate;
    hardwareConfigured_ = true;
    writeTrace("DONE configureHardware");
    return true;
}

bool UsbDacEngine::startSineTest(uint32_t sampleRate, int preferredBitDepth, std::string& outError) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!isOpen_ || !handle_ || !ctx_) {
        outError = "USB DAC not connected";
        return false;
    }

    const uac::AudioStreamAltSetting* chosen = selectPlaybackAltLocked(sampleRate, preferredBitDepth);
    if (!chosen) {
        outError = "DAC does not support PCM playback at " + std::to_string(sampleRate) + " Hz";
        BP_LOGE("%s", outError.c_str());
        return false;
    }
    const uac::AudioStreamAltSetting altCopy = *chosen;

    stopStreamLocked();
    ringBuffer_.reset();

    if (!configureHardwareLocked(altCopy, sampleRate, outError)) {
        return false;
    }

    const size_t frameBytes = pcmFrameBytes(altCopy.pcmFormat, altCopy.channels);
    const size_t targetBufBytes = computeTargetBufferBytes(sampleRate, frameBytes);
    sineGen_.configure(sampleRate, altCopy.pcmFormat, altCopy.channels, 440.0, 0.0631);

    const size_t prefillBytes = ((targetBufBytes * 60u) / 100u / frameBytes) * frameBytes;
    const size_t prefillFrames = prefillBytes / frameBytes;
    std::vector<uint8_t> prefillChunk(prefillFrames * frameBytes);
    sineGen_.generateFrames(prefillChunk.data(), prefillFrames);
    size_t written = ringBuffer_.write(prefillChunk.data(), prefillChunk.size());
    BP_LOGI("Pre-filled SPSC RingBuffer: %zu / %zu bytes (%.1f%% of %d ms buffer)",
            written, targetBufBytes,
            100.0 * static_cast<double>(written) / static_cast<double>(targetBufBytes),
            bufferDurationMs_.load(std::memory_order_relaxed));

    sineRunning_.store(true, std::memory_order_release);
    sineThread_ = std::thread(
        &UsbDacEngine::sineProducerLoop, this, sampleRate, altCopy.pcmFormat, altCopy.channels
    );

    if (!isoStreamer_.start(
            ctx_, handle_, profile_.usbSpeed, altCopy, sampleRate,
            &ringBuffer_, targetBufBytes, !isQualcommPlatform_, outError)) {
        stopStreamLocked();
        return false;
    }

    BP_LOGI("440Hz Bit-Perfect test tone ACTIVE: %u Hz / %u-bit (%s)",
            sampleRate, altCopy.bitResolution, uac::pcmFormatToString(altCopy.pcmFormat));
    return true;
}

bool UsbDacEngine::prepareCaptureStream(
    uint32_t sampleRate,
    int preferredBitDepth,
    CaptureHardwareConfig& outConfig,
    std::string& outError
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!isOpen_ || !handle_ || !ctx_) {
        outError = "USB DAC not connected";
        return false;
    }

    const uac::AudioStreamAltSetting* chosen = selectPlaybackAltLocked(sampleRate, preferredBitDepth);
    if (!chosen) {
        outError = "DAC does not support PCM playback at " + std::to_string(sampleRate) + " Hz";
        BP_LOGE("%s", outError.c_str());
        return false;
    }
    const uac::AudioStreamAltSetting altCopy = *chosen;

    // 1. Stop any running sine generator or previous USB ISO stream cleanly
    stopStreamLocked();

    // 2. Clear SPSC ring buffer & reset drift / dropout diagnostics
    ringBuffer_.reset();
    driftInsertedFrames_.store(0, std::memory_order_relaxed);
    driftDroppedFrames_.store(0, std::memory_order_relaxed);
    zeroDropoutRuns_.store(0, std::memory_order_relaxed);
    zeroDropoutBlocks_.store(0, std::memory_order_relaxed);
    minSpscBytesSeen_.store(SIZE_MAX, std::memory_order_relaxed);
    peakDbfs_.store(-120.0, std::memory_order_relaxed);
    driftCooldownBlocks_ = 0;
    prevBlockHadAudio_ = false;

    // 3. Switch DAC AltSetting & ClockSource + read-back verification
    if (!configureHardwareLocked(altCopy, sampleRate, outError)) {
        return false;
    }

    // 4. Arm live capture pre-fill gate (USB ISO URBs auto-start once buffer hits >= 50% watermark)
    const size_t frameBytes = pcmFrameBytes(altCopy.pcmFormat, altCopy.channels);
    const size_t targetBytes = computeTargetBufferBytes(sampleRate, frameBytes);
    captureDacFormat_ = altCopy.pcmFormat;
    captureDacChannels_ = altCopy.channels;
    captureSampleRate_ = sampleRate;
    captureTargetBytes_.store(targetBytes, std::memory_order_relaxed);
    captureScratch_.reserve(65536u);
    capturePrefilling_.store(true, std::memory_order_release);
    captureArmed_.store(true, std::memory_order_release);

    outConfig.sampleRate = sampleRate;
    outConfig.bitResolution = altCopy.bitResolution;
    outConfig.channels = altCopy.channels;
    outConfig.altSetting = altCopy.altSetting;
    outConfig.pcmFormat = altCopy.pcmFormat;

    BP_LOGI("Capture pipeline armed: DAC IF#%d Alt#%d %s @ %u Hz (waiting for >=60%% of %d ms pre-fill)",
            altCopy.interfaceNumber, altCopy.altSetting,
            uac::pcmFormatToString(altCopy.pcmFormat), sampleRate,
            bufferDurationMs_.load(std::memory_order_relaxed));
    return true;
}

size_t UsbDacEngine::pushCapturedPcmFrames(const uint8_t* data, size_t frameCount, CapturePcmFormat srcFmt) {
    if (!captureArmed_.load(std::memory_order_acquire) || !data || frameCount == 0) {
        return 0;
    }

    const size_t srcFrameBytes = captureBytesPerSample(srcFmt) * static_cast<size_t>(captureDacChannels_);
    if (srcFrameBytes == 0) return 0;
    const size_t lenBytes = frameCount * srcFrameBytes;

    const size_t targetBytes = captureTargetBytes_.load(std::memory_order_relaxed);
    const size_t currentAvail = ringBuffer_.availableRead();
    const bool prefilling = capturePrefilling_.load(std::memory_order_acquire);

    // Dual-clock drift servo: strict 10%..92% Bit-Perfect deadband so normal playback maintains
    // drift(+0/-0) with zero waveform modification; emergency correction only outside [10%, 92%].
    int driftAdjust = 0;
    if (!prefilling && targetBytes > 0) {
        size_t prevMin = minSpscBytesSeen_.load(std::memory_order_relaxed);
        if (currentAvail < prevMin) {
            minSpscBytesSeen_.store(currentAvail, std::memory_order_relaxed);
        }
        const size_t lowWatermark = (targetBytes * 10u) / 100u;
        const size_t highWatermark = (targetBytes * 92u) / 100u;
        if (driftCooldownBlocks_ > 0) {
            --driftCooldownBlocks_;
        } else if (currentAvail < lowWatermark) {
            driftAdjust = 1;
            driftCooldownBlocks_ = 10;
            driftInsertedFrames_.fetch_add(1, std::memory_order_relaxed);
        } else if (currentAvail > highWatermark) {
            driftAdjust = -1;
            driftCooldownBlocks_ = 10;
            driftDroppedFrames_.fetch_add(1, std::memory_order_relaxed);
        }
    }

    CaptureBlockStats stats{};
    const size_t outBytes = convertCapturedPcmBlock(
        data,
        lenBytes,
        srcFmt,
        captureDacFormat_,
        captureDacChannels_,
        driftAdjust,
        captureScratch_,
        stats
    );
    if (outBytes == 0) return 0;

    double dbfs = (stats.peakLinear > 1e-6) ? (20.0 * std::log10(stats.peakLinear)) : -120.0;
    peakDbfs_.store(dbfs, std::memory_order_relaxed);

    // Detect embedded Android r_submix memset(0) silence dropouts inside active music
    if (!prefilling) {
        if (stats.zeroGapRuns > 0 && dbfs > -45.0) {
            zeroDropoutRuns_.fetch_add(stats.zeroGapRuns, std::memory_order_relaxed);
        }
        if (stats.allZeroBlock && prevBlockHadAudio_) {
            zeroDropoutBlocks_.fetch_add(1, std::memory_order_relaxed);
        }
        prevBlockHadAudio_ = (dbfs > -45.0);
    }

    // Hard ceiling guard (strictly lock-free on producer side): prevent SPSC from ever ballooning
    // past targetBytes + 40ms after OS wake-up bursts or buffer slider reductions (no 700ms runaway).
    const size_t dacFrameBytes = pcmFrameBytes(captureDacFormat_, captureDacChannels_);
    size_t bytesToWrite = outBytes;
    if (!prefilling && targetBytes > 0 && dacFrameBytes > 0) {
        const size_t headroomBytes =
            (static_cast<size_t>(captureSampleRate_) * 40u / 1000u) * dacFrameBytes;
        const size_t maxAllowedBytes = targetBytes + headroomBytes;
        if (currentAvail >= maxAllowedBytes) {
            return 0;
        }
        const size_t room = maxAllowedBytes - currentAvail;
        if (bytesToWrite > room) {
            bytesToWrite = (room / dacFrameBytes) * dacFrameBytes;
            if (bytesToWrite == 0) return 0;
        }
    }

    const size_t written = ringBuffer_.write(captureScratch_.data(), bytesToWrite);

    // Pre-fill completion trigger: 75% for low-latency rhythm-game mode (<=85ms, absorbs 24ms DMA ring),
    // 60% for Hi-Fi mode (>85ms, e.g. 60% of 250ms = 150ms, absorbs 48ms DMA ring inside 10%..92% deadband).
    if (prefilling && targetBytes > 0) {
        const int curTargetMs = bufferDurationMs_.load(std::memory_order_relaxed);
        const size_t prefillPct = (curTargetMs <= 85) ? 75u : 60u;
        const size_t prefillThreshold = (targetBytes * prefillPct) / 100u;
        if (ringBuffer_.availableRead() >= prefillThreshold) {
            std::lock_guard<std::mutex> lock(mutex_);
            if (captureArmed_.load(std::memory_order_acquire) &&
                capturePrefilling_.load(std::memory_order_acquire) &&
                isOpen_ && handle_ && ctx_) {
                std::string startErr;
                if (isoStreamer_.start(
                        ctx_, handle_, profile_.usbSpeed, activeAlt_, activeSampleRate_,
                        &ringBuffer_, targetBytes, !isQualcommPlatform_, startErr)) {
                    capturePrefilling_.store(false, std::memory_order_release);
                    BP_LOGI("Pre-fill reached %.1f%% (%zu / %zu bytes) -> USB ISO OUT streaming ACTIVE!",
                            100.0 * static_cast<double>(ringBuffer_.availableRead()) /
                            static_cast<double>(targetBytes),
                            ringBuffer_.availableRead(), targetBytes);
                } else {
                    BP_LOGE("Failed to start USB ISO streamer after pre-fill: %s", startErr.c_str());
                    captureArmed_.store(false, std::memory_order_release);
                }
            }
        }
    }

    return written;
}

void UsbDacEngine::sineProducerLoop(uint32_t sampleRate, uac::PcmFormat fmt, uint8_t channels) {
    const size_t frameBytes = pcmFrameBytes(fmt, channels);
    const size_t chunkFrames = std::max<size_t>(256u, sampleRate / 200u);
    std::vector<uint8_t> chunkBuf(chunkFrames * frameBytes);

    while (sineRunning_.load(std::memory_order_acquire)) {
        const size_t targetBytes = computeTargetBufferBytes(sampleRate, frameBytes);
        const size_t currentFill = ringBuffer_.availableRead();

        if (currentFill < targetBytes) {
            size_t deficitBytes = targetBytes - currentFill;
            size_t framesToGen = std::min(chunkFrames, deficitBytes / frameBytes);
            if (framesToGen > 0) {
                size_t genBytes = sineGen_.generateFrames(chunkBuf.data(), framesToGen);
                ringBuffer_.write(chunkBuf.data(), genBytes);
                continue;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(3));
    }
}

void UsbDacEngine::stopStream() {
    std::lock_guard<std::mutex> lock(mutex_);
    stopStreamLocked();
    if (isOpen_ && handle_) {
        resetAllStreamingInterfacesToAlt0Locked();
        hardwareConfigured_ = false;
    }
}

void UsbDacEngine::stopStreamLocked() {
    captureArmed_.store(false, std::memory_order_release);
    capturePrefilling_.store(false, std::memory_order_release);
    sineRunning_.store(false, std::memory_order_release);
    if (sineThread_.joinable()) {
        sineThread_.join();
    }
    isoStreamer_.stop();
    ringBuffer_.reset();
}

void UsbDacEngine::close(bool restoreUnityVolume) {
    std::lock_guard<std::mutex> lock(mutex_);
    closeLocked(restoreUnityVolume);
}

void UsbDacEngine::closeLocked(bool restoreUnityVolume) {
    const bool wasActive = isoStreamer_.isRunning() || hardwareConfigured_;
    stopStreamLocked();

    if (handle_) {
        // 1. Universal SoC Bus Settle (30ms): wait for host xHCI/MUSB controller DMA to quiesce
        //    after all in-flight URBs have drained, BEFORE switching Alt#0 or releasing interfaces.
        if (wasActive) {
            std::this_thread::sleep_for(std::chrono::milliseconds(30));
        }

        // 2. Return claimed AudioStreaming interfaces (e.g. IF#2) to Alt#0 (zero-bandwidth idle)
        resetAllStreamingInterfacesToAlt0Locked();
        hardwareConfigured_ = false;

        // 3. Restore FeatureUnit volume to 0.00 dB ONLY on final user release (not during rate-switch rebind!)
        if (restoreUnityVolume && isOpen_ && profile_.hasPlaybackFeatureUnit) {
            unmuteFeatureUnitLocked();
            if (profile_.playbackFeatureUnit.masterVolume || profile_.playbackFeatureUnit.channelVolume) {
                std::string volErr;
                if (!setHardwareVolumeDbLocked(0.0, volErr)) {
                    BP_LOGW("Restore 0.00 dB on close failed: %s", volErr.c_str());
                }
            }
        }

        // 4. Progressively release all libusb-claimed interfaces (15ms settle gap between releases)
        //    so non-Qualcomm USB host controllers (XRING / Exynos / Dimensity) never race on usbfs teardown.
        std::set<int> releasedIfaces(claimedInterfaces_.begin(), claimedInterfaces_.end());
        bool firstRelease = true;
        for (int ifNum : claimedInterfaces_) {
            if (!firstRelease) {
                std::this_thread::sleep_for(std::chrono::milliseconds(15));
            }
            firstRelease = false;
            int rc = libusb_release_interface(handle_, ifNum);
            BP_LOGI("libusb_release_interface(IF#%d) -> %s", ifNum, libusb_error_name(rc));
        }
        claimedInterfaces_.clear();

        // 5. Cleanly re-attach Linux snd-usb-audio (IF#0 first -> usb_audio_probe -> IF#2).
        //    libusb_reset_device() is PERMANENTLY FORBIDDEN across all SoCs to prevent xHCI/MUSB
        //    port-hangs and infinite hotplug disconnect/reconnect loops on Exynos/MediaTek/XRING.
        for (int ifNum : releasedIfaces) {
            int activeBefore = libusb_kernel_driver_active(handle_, ifNum);
            if (activeBefore == 0) {
                int rcAttach = libusb_attach_kernel_driver(handle_, ifNum);
                int activeAfter = libusb_kernel_driver_active(handle_, ifNum);
                BP_LOGI("[USB-REATTACH] libusb_attach_kernel_driver(IF#%d) -> %s (kernel_active=%d)",
                        ifNum,
                        rcAttach == LIBUSB_SUCCESS ? "OK" : libusb_error_name(rcAttach),
                        activeAfter);
            } else {
                BP_LOGI("[USB-REATTACH] IF#%d already re-claimed by snd-usb-audio (kernel_active=%d)",
                        ifNum, activeBefore);
            }
        }

        // 6. Close libusb device handle
        libusb_close(handle_);
        handle_ = nullptr;
        BP_LOGI("libusb device handle closed");
        writeTrace("DONE closeLocked");
    }

    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }

    isOpen_ = false;
    activeSampleRate_ = 0;
}

bool UsbDacEngine::isOpen() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return isOpen_;
}

uac::DacDeviceProfile UsbDacEngine::currentProfile() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return profile_;
}

std::string UsbDacEngine::getTelemetryJson() const {
    StreamTelemetry t = isoStreamer_.telemetry();
    const int targetMs = bufferDurationMs_.load(std::memory_order_relaxed);
    const double volDb = currentVolumeDb_.load(std::memory_order_relaxed);
    const bool sineOn = sineRunning_.load(std::memory_order_relaxed);
    const bool capArmed = captureArmed_.load(std::memory_order_relaxed);
    const bool capPrefill = capturePrefilling_.load(std::memory_order_relaxed);
    const uint64_t driftIns = driftInsertedFrames_.load(std::memory_order_relaxed);
    const uint64_t driftDrop = driftDroppedFrames_.load(std::memory_order_relaxed);
    const uint64_t zeroRuns = zeroDropoutRuns_.load(std::memory_order_relaxed);
    const uint64_t zeroBlocks = zeroDropoutBlocks_.load(std::memory_order_relaxed);
    const size_t minSpscBytes = minSpscBytesSeen_.load(std::memory_order_relaxed);
    const double peak = peakDbfs_.load(std::memory_order_relaxed);

    size_t targetBufBytes = t.targetBufferBytes;
    size_t curBufBytes = t.bufferedBytes;
    uint32_t rate = t.sampleRate;
    uac::PcmFormat fmt = t.pcmFormat;
    uint8_t bits = t.bitResolution;

    if (capArmed && !t.streaming) {
        targetBufBytes = captureTargetBytes_.load(std::memory_order_relaxed);
        curBufBytes = ringBuffer_.availableRead();
        rate = captureSampleRate_;
        fmt = captureDacFormat_;
        bits = (fmt == uac::PcmFormat::S16_LE) ? 16 :
               (fmt == uac::PcmFormat::S24_3LE || fmt == uac::PcmFormat::S24_LE_32) ? 24 : 32;
    }

    double fillPct = 0.0;
    if (targetBufBytes > 0) {
        fillPct = std::min(
            100.0,
            100.0 * static_cast<double>(curBufBytes) / static_cast<double>(targetBufBytes)
        );
    }
    double bufferedMs = 0.0;
    double minBufferedMs = 0.0;
    size_t fb = pcmFrameBytes(fmt, 2);
    if (rate > 0 && fb > 0) {
        bufferedMs = (1000.0 * static_cast<double>(curBufBytes)) /
                     (static_cast<double>(rate) * static_cast<double>(fb));
        if (minSpscBytes != SIZE_MAX) {
            minBufferedMs = (1000.0 * static_cast<double>(minSpscBytes)) /
                            (static_cast<double>(rate) * static_cast<double>(fb));
        }
    }

    std::ostringstream oss;
    oss << std::fixed << std::setprecision(2);
    oss << "{";
    oss << "\"streaming\":" << (t.streaming ? "true" : "false") << ",";
    oss << "\"sineRunning\":" << (sineOn ? "true" : "false") << ",";
    oss << "\"captureMode\":" << (capArmed ? "true" : "false") << ",";
    oss << "\"capturePrefilling\":" << (capPrefill ? "true" : "false") << ",";
    oss << "\"sampleRate\":" << rate << ",";
    oss << "\"bitResolution\":" << static_cast<int>(bits) << ",";
    oss << "\"format\":\"" << uac::pcmFormatToString(fmt) << "\",";
    oss << "\"syncType\":\"" << uac::syncTypeToString(t.syncType) << "\",";
    oss << "\"usingFeedback\":" << (t.usingFeedback ? "true" : "false") << ",";
    oss << "\"feedbackHz\":" << t.feedbackHz << ",";
    oss << "\"inFlightUrbs\":" << t.inFlightUrbs << ",";
    oss << "\"packetsPerUrb\":" << t.packetsPerUrb << ",";
    oss << "\"packetsSubmitted\":" << t.packetsSubmitted << ",";
    oss << "\"transferErrors\":" << t.transferErrors << ",";
    oss << "\"underruns\":" << t.underruns << ",";
    oss << "\"bufferedMs\":" << bufferedMs << ",";
    oss << "\"minBufferedMs\":" << minBufferedMs << ",";
    oss << "\"targetBufferMs\":" << targetMs << ",";
    oss << "\"bufferFillPercent\":" << fillPct << ",";
    oss << "\"driftInsertedFrames\":" << driftIns << ",";
    oss << "\"driftDroppedFrames\":" << driftDrop << ",";
    oss << "\"zeroDropoutRuns\":" << zeroRuns << ",";
    oss << "\"zeroDropoutBlocks\":" << zeroBlocks << ",";
    oss << "\"maxUsbGapMs\":" << t.maxCallbackGapMs << ",";
    oss << "\"peakDbfs\":" << peak << ",";
    oss << "\"isoThreadTid\":" << t.isoThreadTid << ",";
    oss << "\"volumeDb\":" << volDb;
    oss << "}";
    return oss.str();
}

} // namespace bitperfect
