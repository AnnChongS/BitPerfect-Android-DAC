#pragma once

#include "uac_types.h"

#include <cstdint>
#include <string>
#include <vector>

#ifndef HOST_UNIT_TEST
struct libusb_device_handle;
#endif

namespace bitperfect::uac {

class UacParser {
public:
    // Pure byte-level parsing methods (unit-testable without libusb hardware)
    static void parseAudioControlExtra(
        const uint8_t* extra,
        int extraLen,
        UacVersion version,
        AudioTopology& outTopology
    );

    static void parseAudioStreamingExtra(
        const uint8_t* extra,
        int extraLen,
        UacVersion version,
        AudioStreamAltSetting& outAlt
    );

    static void parseAudioEndpointExtra(
        const uint8_t* extra,
        int extraLen,
        UacVersion version,
        AudioStreamAltSetting& outAlt
    );

    static bool parseUac2ClockRangeResponse(
        const uint8_t* data,
        int dataLen,
        std::vector<uint32_t>& outRates,
        bool& outContinuous,
        uint32_t& outMinRate,
        uint32_t& outMaxRate
    );

    static PcmFormat classifyPcmFormat(
        bool isPcm,
        bool isFloat,
        uint8_t subslotSize,
        uint8_t bitResolution
    );

    // Resolves UAC2 Clock Source/Selector routing and Playback Feature Unit topology
    static void resolveTopology(DacDeviceProfile& profile);

    // Human-readable multi-line diagnostic report for logcat / UI console
    static std::string formatDiagnosticReport(const DacDeviceProfile& profile);

    // Structured JSON representation for Kotlin Compose UI
    static std::string toJson(const DacDeviceProfile& profile);

#ifndef HOST_UNIT_TEST
    // Full live inspection via wrapped libusb_device_handle (reads descriptors + UAC2 RANGE/FU)
    static bool parseFromLibusbDevice(
        libusb_device_handle* handle,
        uint16_t fallbackVid,
        uint16_t fallbackPid,
        const std::string& fallbackName,
        DacDeviceProfile& outProfile,
        std::string& outError
    );
#endif
};

} // namespace bitperfect::uac
