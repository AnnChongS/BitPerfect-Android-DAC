#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace bitperfect::uac {

enum class UacVersion {
    Unknown = 0,
    Uac1    = 1,
    Uac2    = 2,
    Uac3    = 3
};

enum class PcmFormat {
    Unknown = 0,
    S16_LE,       // 16-bit in 2-byte subslot
    S24_3LE,      // 24-bit packed in 3-byte subslot
    S24_LE_32,    // 24-bit MSB-aligned in 4-byte subslot
    S32_LE,       // 32-bit in 4-byte subslot
    Float32_LE    // 32-bit IEEE 754 float
};

enum class EndpointSyncType : uint8_t {
    None         = 0,
    Asynchronous = 1,
    Adaptive     = 2,
    Synchronous  = 3
};

enum class StreamDirection {
    PlaybackOut = 0,
    CaptureIn   = 1
};

struct ClockSourceInfo {
    uint8_t id = 0;
    uint8_t attributes = 0;       // bmAttributes (bits 1:0 = clock type, bit 2 = synced to SOF)
    uint8_t controls = 0;         // bmControls (bits 1:0 = freq ctrl, bits 3:2 = validity ctrl)
    uint8_t assocTerminal = 0;
    bool freqReadable = false;
    bool freqWritable = false;
    bool validityReadable = false;

    // Populated dynamically via UAC2 RANGE (or CUR fallback) control transfer
    std::vector<uint32_t> supportedRates;
    bool continuousRange = false;
    uint32_t minRate = 0;
    uint32_t maxRate = 0;
};

struct ClockSelectorInfo {
    uint8_t id = 0;
    std::vector<uint8_t> sourceIds; // Index i corresponds to 1-based pin (i + 1)
    uint8_t controls = 0;
    bool selectorWritable = false;
};

struct ClockMultiplierInfo {
    uint8_t id = 0;
    uint8_t sourceId = 0;
    uint8_t controls = 0;
};

struct InputTerminalInfo {
    uint8_t id = 0;
    uint16_t terminalType = 0;
    uint8_t assocTerminal = 0;
    uint8_t clockSourceId = 0; // UAC2 only
    uint8_t channels = 0;
    uint32_t channelConfig = 0;
};

struct OutputTerminalInfo {
    uint8_t id = 0;
    uint16_t terminalType = 0;
    uint8_t assocTerminal = 0;
    uint8_t sourceId = 0;
    uint8_t clockSourceId = 0; // UAC2 only
};

struct GenericUnitInfo {
    uint8_t id = 0;
    uint8_t subtype = 0;
    std::vector<uint8_t> sourceIds;
};

struct FeatureUnitInfo {
    uint8_t id = 0;
    uint8_t sourceId = 0;
    bool masterMute = false;
    bool masterVolume = false;
    bool channelMute = false;
    bool channelVolume = false;
    uint8_t numLogicalChannels = 0; // Excludes master channel 0

    // Optional queried volume range in 1/256 dB units (UAC1 & UAC2 standard Q8.8 fixed-point)
    bool volumeRangeQueried = false;
    int16_t volumeMinQ88 = 0;
    int16_t volumeMaxQ88 = 0;
    int16_t volumeResQ88 = 0;
};

struct AudioTopology {
    int controlInterfaceNumber = -1;
    UacVersion version = UacVersion::Unknown;
    uint16_t bcdAdc = 0;
    std::vector<uint8_t> streamingInterfaceNumbers;

    std::vector<InputTerminalInfo> inputTerminals;
    std::vector<OutputTerminalInfo> outputTerminals;
    std::vector<FeatureUnitInfo> featureUnits;
    std::vector<GenericUnitInfo> routingUnits; // Mixer / Selector units
    std::vector<ClockSourceInfo> clockSources;
    std::vector<ClockSelectorInfo> clockSelectors;
    std::vector<ClockMultiplierInfo> clockMultipliers;
};

struct RateClockRoute {
    uint32_t sampleRate = 0;
    uint8_t clockSourceId = 0;
    uint8_t clockSelectorId = 0; // 0 if directly wired without Clock Selector
    uint8_t clockSelectorPin = 0; // 1-based selector pin index
};

struct AudioStreamAltSetting {
    int interfaceNumber = -1;
    int altSetting = 0;
    UacVersion version = UacVersion::Unknown;
    StreamDirection direction = StreamDirection::PlaybackOut;

    // AS_GENERAL / FORMAT_TYPE
    uint8_t terminalLink = 0;
    uint8_t formatType = 0;
    uint32_t rawFormatTagOrMask = 0;
    PcmFormat pcmFormat = PcmFormat::Unknown;
    uint8_t channels = 0;
    uint8_t subslotSize = 0;      // Bytes per sample slot (2, 3, or 4)
    uint8_t bitResolution = 0;    // Valid bits per sample (16, 24, or 32)
    bool isPcm = false;

    // Primary Isochronous Data Endpoint
    uint8_t dataEndpointAddr = 0;
    uint16_t maxPacketSizeRaw = 0;
    uint32_t maxPacketBytesPerInterval = 0; // Accounts for USB HS high-bandwidth multiplier
    uint8_t interval = 0;
    EndpointSyncType syncType = EndpointSyncType::None;
    bool implicitFeedback = false;

    // Explicit Feedback Endpoint (UAC1 bSynchAddress or UAC2 sibling IN Feedback EP)
    bool hasFeedbackEndpoint = false;
    uint8_t feedbackEndpointAddr = 0;
    uint16_t feedbackMaxPacketSize = 0;
    uint8_t feedbackInterval = 0;
    uint8_t feedbackRefresh = 0; // UAC1 bRefresh (2^bRefresh ms)

    // CS_ENDPOINT (EP_GENERAL)
    bool uac1SamplingFreqControl = false;
    bool maxPacketsOnly = false;

    // UAC2 Clock Routing (resolved from InputTerminal -> ClockSource/ClockSelector)
    uint8_t primaryClockSourceId = 0;
    uint8_t clockSelectorId = 0;
    std::vector<RateClockRoute> rateClockRoutes;

    // Supported sample rates (from FORMAT_TYPE in UAC1, or ClockSource RANGE in UAC2)
    std::vector<uint32_t> sampleRates;
    bool continuousRate = false;
    uint32_t minSampleRate = 0;
    uint32_t maxSampleRate = 0;
};

struct DacDeviceProfile {
    uint16_t vendorId = 0;
    uint16_t productId = 0;
    std::string manufacturer;
    std::string productName;
    std::string serialNumber;
    int usbSpeed = 0; // Matches libusb_speed enum value
    std::string usbSpeedName = "Unknown";

    UacVersion uacVersion = UacVersion::Unknown;
    AudioTopology topology;

    std::vector<AudioStreamAltSetting> playbackAltSettings;
    std::vector<AudioStreamAltSetting> captureAltSettings;

    // Resolved Feature Unit along playback USB-Streaming -> OutputTerminal path
    bool hasPlaybackFeatureUnit = false;
    FeatureUnitInfo playbackFeatureUnit;

    // All Audio Interface numbers (AC + AS) discovered on this device
    std::vector<int> audioInterfaceNumbers;
};

// Formatting helpers
const char* uacVersionToString(UacVersion v);
const char* pcmFormatToString(PcmFormat fmt);
const char* syncTypeToString(EndpointSyncType s);
const char* terminalTypeToString(uint16_t type);

} // namespace bitperfect::uac
