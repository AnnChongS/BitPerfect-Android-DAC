#include "uac_parser.h"
#include "native_log.h"
#include "uac_constants.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <iomanip>
#include <set>
#include <sstream>

#ifndef HOST_UNIT_TEST
#include <libusb.h>
#endif

namespace bitperfect::uac {

namespace {

inline uint16_t readLe16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

inline int16_t readLeS16(const uint8_t* p) {
    return static_cast<int16_t>(readLe16(p));
}

inline uint32_t readLe24(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16);
}

inline uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

void sortUniqueRates(std::vector<uint32_t>& rates) {
    std::sort(rates.begin(), rates.end());
    rates.erase(std::unique(rates.begin(), rates.end()), rates.end());
}

std::string jsonEscape(const std::string& input) {
    std::ostringstream oss;
    for (unsigned char c : input) {
        switch (c) {
            case '"':  oss << "\\\""; break;
            case '\\': oss << "\\\\"; break;
            case '\b': oss << "\\b";  break;
            case '\f': oss << "\\f";  break;
            case '\n': oss << "\\n";  break;
            case '\r': oss << "\\r";  break;
            case '\t': oss << "\\t";  break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    oss << buf;
                } else {
                    oss << static_cast<char>(c);
                }
        }
    }
    return oss.str();
}

const ClockSourceInfo* findClockSource(const AudioTopology& topo, uint8_t id) {
    for (const auto& cs : topo.clockSources) {
        if (cs.id == id) return &cs;
    }
    return nullptr;
}

const ClockSelectorInfo* findClockSelector(const AudioTopology& topo, uint8_t id) {
    for (const auto& sel : topo.clockSelectors) {
        if (sel.id == id) return &sel;
    }
    return nullptr;
}

const ClockMultiplierInfo* findClockMultiplier(const AudioTopology& topo, uint8_t id) {
    for (const auto& mul : topo.clockMultipliers) {
        if (mul.id == id) return &mul;
    }
    return nullptr;
}

const InputTerminalInfo* findInputTerminal(const AudioTopology& topo, uint8_t id) {
    for (const auto& it : topo.inputTerminals) {
        if (it.id == id) return &it;
    }
    return nullptr;
}

const OutputTerminalInfo* findOutputTerminal(const AudioTopology& topo, uint8_t id) {
    for (const auto& ot : topo.outputTerminals) {
        if (ot.id == id) return &ot;
    }
    return nullptr;
}

const FeatureUnitInfo* findFeatureUnit(const AudioTopology& topo, uint8_t id) {
    for (const auto& fu : topo.featureUnits) {
        if (fu.id == id) return &fu;
    }
    return nullptr;
}

const GenericUnitInfo* findRoutingUnit(const AudioTopology& topo, uint8_t id) {
    for (const auto& ru : topo.routingUnits) {
        if (ru.id == id) return &ru;
    }
    return nullptr;
}

// Follows ClockMultiplier chain until reaching a ClockSource (cycle-safe)
const ClockSourceInfo* traceToClockSource(
    const AudioTopology& topo,
    uint8_t clockEntityId,
    std::set<uint8_t>& visited
) {
    if (clockEntityId == 0 || !visited.insert(clockEntityId).second) {
        return nullptr;
    }
    if (const auto* cs = findClockSource(topo, clockEntityId)) {
        return cs;
    }
    if (const auto* mul = findClockMultiplier(topo, clockEntityId)) {
        return traceToClockSource(topo, mul->sourceId, visited);
    }
    return nullptr;
}

// Recursively traces upstream from an entity ID looking for USB_STREAMING InputTerminal
// and records any FeatureUnit encountered along the valid playback path.
bool tracePlaybackPathToUsbTerminal(
    const AudioTopology& topo,
    uint8_t entityId,
    std::set<uint8_t>& visited,
    std::vector<const FeatureUnitInfo*>& foundFUs
) {
    if (entityId == 0 || !visited.insert(entityId).second) {
        return false;
    }

    if (const auto* it = findInputTerminal(topo, entityId)) {
        return it->terminalType == TERMINAL_USB_STREAMING;
    }

    if (const auto* fu = findFeatureUnit(topo, entityId)) {
        if (tracePlaybackPathToUsbTerminal(topo, fu->sourceId, visited, foundFUs)) {
            foundFUs.push_back(fu);
            return true;
        }
        return false;
    }

    if (const auto* ru = findRoutingUnit(topo, entityId)) {
        for (uint8_t src : ru->sourceIds) {
            std::set<uint8_t> branchVisited = visited;
            if (tracePlaybackPathToUsbTerminal(topo, src, branchVisited, foundFUs)) {
                return true;
            }
        }
        return false;
    }

    return false;
}

#ifndef HOST_UNIT_TEST

const char* libusbSpeedToString(int speed) {
    switch (speed) {
        case LIBUSB_SPEED_LOW:        return "Low Speed (1.5 Mbps)";
        case LIBUSB_SPEED_FULL:       return "Full Speed (12 Mbps)";
        case LIBUSB_SPEED_HIGH:       return "High Speed (480 Mbps)";
        case LIBUSB_SPEED_SUPER:      return "SuperSpeed (5 Gbps)";
        case LIBUSB_SPEED_SUPER_PLUS: return "SuperSpeed+ (10 Gbps)";
        default:                      return "Unknown Speed";
    }
}

std::string readUsbStringDescriptor(libusb_device_handle* handle, uint8_t index) {
    if (!handle || index == 0) return "";
    unsigned char buf[256] = {0};
    int r = libusb_get_string_descriptor_ascii(handle, index, buf, sizeof(buf) - 1);
    if (r > 0) {
        return std::string(reinterpret_cast<char*>(buf), static_cast<size_t>(r));
    }
    return "";
}

// Queries UAC 2.0 Clock Source supported sample rates using RANGE (0x02),
// with fallback to single-shot 256B read and CUR (0x01) for quirky DAC firmwares.
void queryUac2ClockSourceRates(
    libusb_device_handle* handle,
    int acInterfaceNum,
    ClockSourceInfo& cs
) {
    constexpr unsigned int kTimeoutMs = 1000;
    const uint16_t wValue = static_cast<uint16_t>(UAC2_CS_SAM_FREQ_CONTROL << 8);
    const uint16_t wIndex = static_cast<uint16_t>((static_cast<uint16_t>(cs.id) << 8) |
                                                  (static_cast<uint16_t>(acInterfaceNum) & 0xFF));

    // Stage 1: Probe 2-byte wNumSubRanges header (Linux snd-usb-audio strategy)
    uint8_t hdr[2] = {0, 0};
    int rc = libusb_control_transfer(
        handle,
        REQ_TYPE_CLASS_IF_IN,
        UAC2_REQ_RANGE,
        wValue,
        wIndex,
        hdr,
        sizeof(hdr),
        kTimeoutMs
    );

    std::vector<uint8_t> rangeBuf;
    bool gotRange = false;

    if (rc == 2) {
        uint16_t numSubRanges = readLe16(hdr);
        if (numSubRanges > 0 && numSubRanges <= 64) {
            size_t totalBytes = 2u + static_cast<size_t>(numSubRanges) * 12u;
            rangeBuf.resize(totalBytes, 0);
            int rc2 = libusb_control_transfer(
                handle,
                REQ_TYPE_CLASS_IF_IN,
                UAC2_REQ_RANGE,
                wValue,
                wIndex,
                rangeBuf.data(),
                static_cast<uint16_t>(totalBytes),
                kTimeoutMs
            );
            if (rc2 >= 14) {
                rangeBuf.resize(static_cast<size_t>(rc2));
                gotRange = true;
            }
        }
    }

    // Fallback: Some DACs stall on 2-byte RANGE probes; try single 256-byte read
    if (!gotRange) {
        rangeBuf.assign(256, 0);
        int rcFallback = libusb_control_transfer(
            handle,
            REQ_TYPE_CLASS_IF_IN,
            UAC2_REQ_RANGE,
            wValue,
            wIndex,
            rangeBuf.data(),
            static_cast<uint16_t>(rangeBuf.size()),
            kTimeoutMs
        );
        if (rcFallback >= 14) {
            rangeBuf.resize(static_cast<size_t>(rcFallback));
            gotRange = true;
        } else {
            BP_LOGW("UAC2 ClockSource #%u RANGE query failed (probe=%d, fallback=%d)",
                    cs.id, rc, rcFallback);
        }
    }

    if (gotRange) {
        UacParser::parseUac2ClockRangeResponse(
            rangeBuf.data(),
            static_cast<int>(rangeBuf.size()),
            cs.supportedRates,
            cs.continuousRange,
            cs.minRate,
            cs.maxRate
        );
        return;
    }

    // Last resort: query CUR (0x01) to at least detect active locked rate
    uint8_t curBuf[4] = {0};
    int rcCur = libusb_control_transfer(
        handle,
        REQ_TYPE_CLASS_IF_IN,
        UAC2_REQ_CUR,
        wValue,
        wIndex,
        curBuf,
        sizeof(curBuf),
        kTimeoutMs
    );
    if (rcCur == 4) {
        uint32_t curRate = readLe32(curBuf);
        if (curRate > 0) {
            cs.supportedRates.push_back(curRate);
            BP_LOGW("UAC2 ClockSource #%u RANGE unavailable; using CUR=%u Hz", cs.id, curRate);
        }
    }
}

void queryFeatureUnitVolumeRange(
    libusb_device_handle* handle,
    int acInterfaceNum,
    UacVersion version,
    FeatureUnitInfo& fu
) {
    if (!fu.masterVolume && !fu.channelVolume) return;
    constexpr unsigned int kTimeoutMs = 500;
    const uint8_t ch = fu.masterVolume ? 0 : 1;
    const uint16_t wValue = static_cast<uint16_t>((FU_VOLUME_CONTROL << 8) | ch);
    const uint16_t wIndex = static_cast<uint16_t>((static_cast<uint16_t>(fu.id) << 8) |
                                                  (static_cast<uint16_t>(acInterfaceNum) & 0xFF));

    if (version == UacVersion::Uac2) {
        // UAC2 Layout 2 RANGE block: 2B wNumSubRanges + N * (2B wMIN, 2B wMAX, 2B wRES)
        uint8_t buf[64] = {0};
        int rc = libusb_control_transfer(
            handle, REQ_TYPE_CLASS_IF_IN, UAC2_REQ_RANGE, wValue, wIndex, buf, sizeof(buf), kTimeoutMs
        );
        if (rc >= 8) {
            uint16_t numSubRanges = readLe16(buf);
            if (numSubRanges >= 1) {
                fu.volumeMinQ88 = readLeS16(buf + 2);
                fu.volumeMaxQ88 = readLeS16(buf + 4);
                fu.volumeResQ88 = readLeS16(buf + 6);
                fu.volumeRangeQueried = true;
            }
        }
    } else if (version == UacVersion::Uac1) {
        uint8_t minBuf[2] = {0}, maxBuf[2] = {0}, resBuf[2] = {0};
        int r1 = libusb_control_transfer(
            handle, REQ_TYPE_CLASS_IF_IN, UAC1_GET_MIN, wValue, wIndex, minBuf, 2, kTimeoutMs
        );
        int r2 = libusb_control_transfer(
            handle, REQ_TYPE_CLASS_IF_IN, UAC1_GET_MAX, wValue, wIndex, maxBuf, 2, kTimeoutMs
        );
        int r3 = libusb_control_transfer(
            handle, REQ_TYPE_CLASS_IF_IN, UAC1_GET_RES, wValue, wIndex, resBuf, 2, kTimeoutMs
        );
        if (r1 == 2 && r2 == 2) {
            fu.volumeMinQ88 = readLeS16(minBuf);
            fu.volumeMaxQ88 = readLeS16(maxBuf);
            fu.volumeResQ88 = (r3 == 2) ? readLeS16(resBuf) : 256;
            fu.volumeRangeQueried = true;
        }
    }
}

#endif // HOST_UNIT_TEST

} // namespace

const char* uacVersionToString(UacVersion v) {
    switch (v) {
        case UacVersion::Uac1: return "UAC 1.0";
        case UacVersion::Uac2: return "UAC 2.0";
        case UacVersion::Uac3: return "UAC 3.0";
        default:               return "Unknown";
    }
}

const char* pcmFormatToString(PcmFormat fmt) {
    switch (fmt) {
        case PcmFormat::S16_LE:     return "S16_LE (16-bit)";
        case PcmFormat::S24_3LE:    return "S24_3LE (24-bit packed)";
        case PcmFormat::S24_LE_32:  return "S24_LE (24-in-32-bit)";
        case PcmFormat::S32_LE:     return "S32_LE (32-bit)";
        case PcmFormat::Float32_LE: return "FLOAT32_LE (32-bit float)";
        default:                    return "Unsupported/Non-PCM";
    }
}

const char* syncTypeToString(EndpointSyncType s) {
    switch (s) {
        case EndpointSyncType::Asynchronous: return "Asynchronous";
        case EndpointSyncType::Adaptive:     return "Adaptive";
        case EndpointSyncType::Synchronous:  return "Synchronous";
        default:                             return "None";
    }
}

const char* acUnitSubtypeToString(uint8_t subtype, UacVersion ver) {
    switch (subtype) {
        case AC_MIXER_UNIT:            return "MixerUnit(0x04)";
        case AC_SELECTOR_UNIT:         return "SelectorUnit(0x05)";
        case AC_EFFECT_UNIT:           return (ver == UacVersion::Uac1) ? "ProcessingUnit(0x07)" : "EffectUnit(0x07)";
        case AC_PROCESSING_UNIT:       return (ver == UacVersion::Uac1) ? "ExtensionUnit(0x08)" : "ProcessingUnit(0x08)";
        case AC_EXTENSION_UNIT:        return "ExtensionUnit(0x09)";
        case AC_SAMPLE_RATE_CONVERTER: return "SampleRateConverter(0x0D)";
        default:                       return "UnknownUnit";
    }
}

const char* terminalTypeToString(uint16_t type) {
    switch (type) {
        case TERMINAL_USB_STREAMING:    return "USB Streaming";
        case TERMINAL_SPEAKER:          return "Speaker";
        case TERMINAL_HEADPHONES:       return "Headphones";
        case TERMINAL_DESKTOP_SPEAKER:  return "Desktop Speaker";
        case TERMINAL_LINE_CONNECTOR:   return "Line Connector";
        case TERMINAL_SPDIF_INTERFACE:  return "S/PDIF Interface";
        default:                        return "Other Terminal";
    }
}

PcmFormat UacParser::classifyPcmFormat(
    bool isPcm,
    bool isFloat,
    uint8_t subslotSize,
    uint8_t bitResolution
) {
    if (isFloat && subslotSize == 4 && bitResolution == 32) {
        return PcmFormat::Float32_LE;
    }
    if (!isPcm) {
        return PcmFormat::Unknown;
    }
    if (subslotSize == 2 && bitResolution == 16) {
        return PcmFormat::S16_LE;
    }
    if (subslotSize == 3 && bitResolution == 24) {
        return PcmFormat::S24_3LE;
    }
    if (subslotSize == 4 && bitResolution == 24) {
        return PcmFormat::S24_LE_32;
    }
    if (subslotSize == 4 && bitResolution == 32) {
        return PcmFormat::S32_LE;
    }
    return PcmFormat::Unknown;
}

void UacParser::parseAudioControlExtra(
    const uint8_t* extra,
    int extraLen,
    UacVersion version,
    AudioTopology& outTopology
) {
    if (!extra || extraLen <= 0) return;

    if (outTopology.version == UacVersion::Unknown && version != UacVersion::Unknown) {
        outTopology.version = version;
    }

    int offset = 0;
    while (offset + 2 <= extraLen) {
        const uint8_t bLength = extra[offset];
        const uint8_t bDescriptorType = extra[offset + 1];

        if (bLength < 2 || offset + bLength > extraLen) {
            BP_LOGW("Malformed AC descriptor at offset %d (bLength=%u, remaining=%d)",
                    offset, bLength, extraLen - offset);
            break;
        }

        if (bDescriptorType == DT_CS_INTERFACE && bLength >= 3) {
            const uint8_t* p = extra + offset;
            const uint8_t subtype = p[2];
            UacVersion effectiveVer = (outTopology.version != UacVersion::Unknown)
                                      ? outTopology.version : version;

            switch (subtype) {
                case AC_HEADER: {
                    if (bLength >= 7) {
                        outTopology.bcdAdc = readLe16(p + 3);
                        if (outTopology.version == UacVersion::Unknown) {
                            if (outTopology.bcdAdc >= 0x0300) {
                                outTopology.version = UacVersion::Uac3;
                            } else if (outTopology.bcdAdc >= 0x0200) {
                                outTopology.version = UacVersion::Uac2;
                            } else {
                                outTopology.version = UacVersion::Uac1;
                            }
                            effectiveVer = outTopology.version;
                        }
                        if (effectiveVer == UacVersion::Uac1 && bLength >= 8) {
                            uint8_t n = p[7];
                            for (uint8_t i = 0; i < n && (8 + i) < bLength; ++i) {
                                outTopology.streamingInterfaceNumbers.push_back(p[8 + i]);
                            }
                        }
                    }
                    break;
                }

                case AC_INPUT_TERMINAL: {
                    if (effectiveVer == UacVersion::Uac2 && bLength >= 17) {
                        InputTerminalInfo it{};
                        it.id = p[3];
                        it.terminalType = readLe16(p + 4);
                        it.assocTerminal = p[6];
                        it.clockSourceId = p[7];
                        it.channels = p[8];
                        it.channelConfig = readLe32(p + 9);
                        outTopology.inputTerminals.push_back(it);
                    } else if (effectiveVer == UacVersion::Uac1 && bLength >= 12) {
                        InputTerminalInfo it{};
                        it.id = p[3];
                        it.terminalType = readLe16(p + 4);
                        it.assocTerminal = p[6];
                        it.channels = p[7];
                        it.channelConfig = readLe16(p + 8);
                        outTopology.inputTerminals.push_back(it);
                    }
                    break;
                }

                case AC_OUTPUT_TERMINAL: {
                    if (effectiveVer == UacVersion::Uac2 && bLength >= 12) {
                        OutputTerminalInfo ot{};
                        ot.id = p[3];
                        ot.terminalType = readLe16(p + 4);
                        ot.assocTerminal = p[6];
                        ot.sourceId = p[7];
                        ot.clockSourceId = p[8];
                        outTopology.outputTerminals.push_back(ot);
                    } else if (effectiveVer == UacVersion::Uac1 && bLength >= 9) {
                        OutputTerminalInfo ot{};
                        ot.id = p[3];
                        ot.terminalType = readLe16(p + 4);
                        ot.assocTerminal = p[6];
                        ot.sourceId = p[7];
                        outTopology.outputTerminals.push_back(ot);
                    }
                    break;
                }

                case AC_MIXER_UNIT:
                case AC_SELECTOR_UNIT: {
                    if (bLength >= 5) {
                        GenericUnitInfo ru{};
                        ru.id = p[3];
                        ru.subtype = subtype;
                        uint8_t nrInPins = p[4];
                        for (uint8_t i = 0; i < nrInPins && (5 + i) < bLength; ++i) {
                            ru.sourceIds.push_back(p[5 + i]);
                        }
                        outTopology.routingUnits.push_back(std::move(ru));
                    }
                    break;
                }

                case AC_EFFECT_UNIT: {
                    // UAC2 Effect Unit (0x07): [bLength, 0x24, 0x07, bUnitID, wEffectType(2B), bSourceID, ...]
                    // UAC1 Processing Unit (0x07): [bLength, 0x24, 0x07, bUnitID, wProcessType(2B), bNrInPins, baSourceID...]
                    if (effectiveVer == UacVersion::Uac2 && bLength >= 7) {
                        GenericUnitInfo ru{};
                        ru.id = p[3];
                        ru.subtype = subtype;
                        ru.sourceIds.push_back(p[6]);
                        outTopology.routingUnits.push_back(std::move(ru));
                    } else if (effectiveVer == UacVersion::Uac1 && bLength >= 7) {
                        GenericUnitInfo ru{};
                        ru.id = p[3];
                        ru.subtype = subtype;
                        uint8_t nrInPins = p[6];
                        for (uint8_t i = 0; i < nrInPins && (7 + i) < bLength; ++i) {
                            ru.sourceIds.push_back(p[7 + i]);
                        }
                        outTopology.routingUnits.push_back(std::move(ru));
                    }
                    break;
                }

                case AC_PROCESSING_UNIT:
                case AC_EXTENSION_UNIT: {
                    // UAC2 Processing (0x08) / Extension (0x09) & UAC1 Extension (0x08):
                    // [bLength, 0x24, subtype, bUnitID, wType/Code(2B), bNrInPins, baSourceID(1..p), ...]
                    if (bLength >= 7) {
                        GenericUnitInfo ru{};
                        ru.id = p[3];
                        ru.subtype = subtype;
                        uint8_t nrInPins = p[6];
                        for (uint8_t i = 0; i < nrInPins && (7 + i) < bLength; ++i) {
                            ru.sourceIds.push_back(p[7 + i]);
                        }
                        outTopology.routingUnits.push_back(std::move(ru));
                    }
                    break;
                }

                case AC_SAMPLE_RATE_CONVERTER: {
                    // UAC2 SRC Unit (0x0D): [bLength, 0x24, 0x0D, bUnitID, bSourceID, bCSourceInID, bCSourceOutID, iSRC]
                    if (effectiveVer == UacVersion::Uac2 && bLength >= 7) {
                        GenericUnitInfo ru{};
                        ru.id = p[3];
                        ru.subtype = subtype;
                        ru.sourceIds.push_back(p[4]);
                        outTopology.routingUnits.push_back(std::move(ru));
                    }
                    break;
                }

                case AC_FEATURE_UNIT: {
                    if (effectiveVer == UacVersion::Uac2 && bLength >= 10) {
                        FeatureUnitInfo fu{};
                        fu.id = p[3];
                        fu.sourceId = p[4];
                        // UAC2: 4-byte bmaControls[] starting at offset 5, trailing 1-byte iFeature
                        int numEntries = (bLength - 6) / 4;
                        if (numEntries > 0) {
                            uint32_t masterCtrl = readLe32(p + 5);
                            fu.masterMute   = ((masterCtrl >> 0) & 0x03u) == 0x03u;
                            fu.masterVolume = ((masterCtrl >> 2) & 0x03u) == 0x03u;
                            fu.numLogicalChannels = static_cast<uint8_t>(numEntries - 1);
                            for (int ch = 1; ch < numEntries; ++ch) {
                                uint32_t chCtrl = readLe32(p + 5 + ch * 4);
                                if (((chCtrl >> 0) & 0x03u) == 0x03u) fu.channelMute = true;
                                if (((chCtrl >> 2) & 0x03u) == 0x03u) fu.channelVolume = true;
                            }
                        }
                        outTopology.featureUnits.push_back(fu);
                    } else if (effectiveVer == UacVersion::Uac1 && bLength >= 7) {
                        FeatureUnitInfo fu{};
                        fu.id = p[3];
                        fu.sourceId = p[4];
                        uint8_t ctrlSize = p[5];
                        if (ctrlSize >= 1) {
                            int numEntries = (bLength - 7) / ctrlSize;
                            if (numEntries > 0) {
                                uint8_t masterCtrl = p[6];
                                fu.masterMute   = (masterCtrl & 0x01u) != 0;
                                fu.masterVolume = (masterCtrl & 0x02u) != 0;
                                fu.numLogicalChannels = static_cast<uint8_t>(numEntries - 1);
                                for (int ch = 1; ch < numEntries; ++ch) {
                                    uint8_t chCtrl = p[6 + ch * ctrlSize];
                                    if (chCtrl & 0x01u) fu.channelMute = true;
                                    if (chCtrl & 0x02u) fu.channelVolume = true;
                                }
                            }
                        }
                        outTopology.featureUnits.push_back(fu);
                    }
                    break;
                }

                case AC_CLOCK_SOURCE: {
                    if (effectiveVer == UacVersion::Uac2 && bLength >= 8) {
                        ClockSourceInfo cs{};
                        cs.id = p[3];
                        cs.attributes = p[4];
                        cs.controls = p[5];
                        cs.assocTerminal = p[6];
                        cs.freqReadable     = (cs.controls & 0x03u) != 0;
                        cs.freqWritable     = (cs.controls & 0x03u) == 0x03u;
                        cs.validityReadable = ((cs.controls >> 2) & 0x03u) != 0;
                        outTopology.clockSources.push_back(cs);
                    }
                    break;
                }

                case AC_CLOCK_SELECTOR: {
                    if (effectiveVer == UacVersion::Uac2 && bLength >= 6) {
                        ClockSelectorInfo sel{};
                        sel.id = p[3];
                        uint8_t nrInPins = p[4];
                        if (5 + nrInPins <= bLength) {
                            for (uint8_t i = 0; i < nrInPins; ++i) {
                                sel.sourceIds.push_back(p[5 + i]);
                            }
                            if (5 + nrInPins < bLength) {
                                sel.controls = p[5 + nrInPins];
                                sel.selectorWritable = (sel.controls & 0x03u) == 0x03u;
                            }
                            outTopology.clockSelectors.push_back(std::move(sel));
                        }
                    }
                    break;
                }

                case AC_CLOCK_MULTIPLIER: {
                    if (effectiveVer == UacVersion::Uac2 && bLength >= 7) {
                        ClockMultiplierInfo mul{};
                        mul.id = p[3];
                        mul.sourceId = p[4];
                        mul.controls = p[5];
                        outTopology.clockMultipliers.push_back(mul);
                    }
                    break;
                }

                default:
                    BP_LOGD("Unhandled AC CS_INTERFACE subtype=0x%02X len=%u", subtype, bLength);
                    break;
            }
        }

        offset += bLength;
    }
}

void UacParser::parseAudioStreamingExtra(
    const uint8_t* extra,
    int extraLen,
    UacVersion version,
    AudioStreamAltSetting& outAlt
) {
    if (!extra || extraLen <= 0) return;

    bool isPcm = false;
    bool isFloat = false;

    int offset = 0;
    while (offset + 2 <= extraLen) {
        const uint8_t bLength = extra[offset];
        const uint8_t bDescriptorType = extra[offset + 1];

        if (bLength < 2 || offset + bLength > extraLen) {
            BP_LOGW("Malformed AS descriptor at offset %d (bLength=%u, remaining=%d)",
                    offset, bLength, extraLen - offset);
            break;
        }

        if (bDescriptorType == DT_CS_INTERFACE && bLength >= 3) {
            const uint8_t* p = extra + offset;
            const uint8_t subtype = p[2];

            if (subtype == AS_GENERAL) {
                if (version == UacVersion::Uac2 && bLength >= 16) {
                    outAlt.terminalLink = p[3];
                    outAlt.formatType = p[5];
                    uint32_t bmFormats = readLe32(p + 6);
                    outAlt.rawFormatTagOrMask = bmFormats;
                    outAlt.channels = p[10];
                    isPcm   = (bmFormats & UAC2_FORMAT_PCM) != 0;
                    isFloat = (bmFormats & UAC2_FORMAT_IEEE_FLOAT) != 0;
                } else if (version == UacVersion::Uac1 && bLength >= 7) {
                    outAlt.terminalLink = p[3];
                    uint16_t wFormatTag = readLe16(p + 5);
                    outAlt.rawFormatTagOrMask = wFormatTag;
                    isPcm   = (wFormatTag == UAC1_FORMAT_PCM);
                    isFloat = (wFormatTag == UAC1_FORMAT_IEEE_FLOAT);
                }
            } else if (subtype == AS_FORMAT_TYPE && bLength >= 4) {
                outAlt.formatType = p[3];
                if (outAlt.formatType == FORMAT_TYPE_I) {
                    if (version == UacVersion::Uac2 && bLength >= 6) {
                        outAlt.subslotSize = p[4];
                        outAlt.bitResolution = p[5];
                    } else if (version == UacVersion::Uac1 && bLength >= 8) {
                        outAlt.channels = p[4];
                        outAlt.subslotSize = p[5];
                        outAlt.bitResolution = p[6];
                        uint8_t samFreqType = p[7];
                        if (samFreqType == 0) {
                            if (bLength >= 14) {
                                outAlt.continuousRate = true;
                                outAlt.minSampleRate = readLe24(p + 8);
                                outAlt.maxSampleRate = readLe24(p + 11);
                                for (uint32_t stdRate : STANDARD_SAMPLE_RATES) {
                                    if (stdRate >= outAlt.minSampleRate &&
                                        stdRate <= outAlt.maxSampleRate) {
                                        outAlt.sampleRates.push_back(stdRate);
                                    }
                                }
                            }
                        } else {
                            for (uint8_t i = 0; i < samFreqType; ++i) {
                                int pos = 8 + static_cast<int>(i) * 3;
                                if (pos + 3 > bLength) break;
                                uint32_t rate = readLe24(p + pos);
                                if (rate > 0) {
                                    outAlt.sampleRates.push_back(rate);
                                }
                            }
                            sortUniqueRates(outAlt.sampleRates);
                        }
                    }
                }
            }
        }

        offset += bLength;
    }

    outAlt.isPcm = isPcm;
    outAlt.pcmFormat = classifyPcmFormat(isPcm, isFloat, outAlt.subslotSize, outAlt.bitResolution);
}

void UacParser::parseAudioEndpointExtra(
    const uint8_t* extra,
    int extraLen,
    UacVersion version,
    AudioStreamAltSetting& outAlt
) {
    if (!extra || extraLen <= 0) return;

    int offset = 0;
    while (offset + 2 <= extraLen) {
        const uint8_t bLength = extra[offset];
        const uint8_t bDescriptorType = extra[offset + 1];

        if (bLength < 2 || offset + bLength > extraLen) {
            break;
        }

        if (bDescriptorType == DT_CS_ENDPOINT && bLength >= 4 && extra[offset + 2] == EP_GENERAL) {
            uint8_t bmAttributes = extra[offset + 3];
            outAlt.maxPacketsOnly = (bmAttributes & 0x80u) != 0;
            if (version == UacVersion::Uac1) {
                outAlt.uac1SamplingFreqControl = (bmAttributes & 0x01u) != 0;
            }
        }

        offset += bLength;
    }
}

bool UacParser::parseUac2ClockRangeResponse(
    const uint8_t* data,
    int dataLen,
    std::vector<uint32_t>& outRates,
    bool& outContinuous,
    uint32_t& outMinRate,
    uint32_t& outMaxRate
) {
    if (!data || dataLen < 14) return false;

    uint16_t numSubRanges = readLe16(data);
    if (numSubRanges == 0) return false;

    outContinuous = false;
    outMinRate = 0;
    outMaxRate = 0;

    int maxReadable = (dataLen - 2) / 12;
    int count = std::min<int>(numSubRanges, maxReadable);

    for (int i = 0; i < count; ++i) {
        const uint8_t* entry = data + 2 + i * 12;
        uint32_t dMin = readLe32(entry + 0);
        uint32_t dMax = readLe32(entry + 4);
        uint32_t dRes = readLe32(entry + 8);

        if (dMin == 0 || dMax < dMin) continue;

        if (dMin == dMax) {
            outRates.push_back(dMin);
        } else {
            outContinuous = true;
            if (outMinRate == 0 || dMin < outMinRate) outMinRate = dMin;
            if (dMax > outMaxRate) outMaxRate = dMax;
            for (uint32_t stdRate : STANDARD_SAMPLE_RATES) {
                if (stdRate >= dMin && stdRate <= dMax) {
                    if (dRes == 0 || ((stdRate - dMin) % dRes == 0)) {
                        outRates.push_back(stdRate);
                    }
                }
            }
        }
    }

    sortUniqueRates(outRates);
    return !outRates.empty();
}

void UacParser::resolveTopology(DacDeviceProfile& profile) {
    const AudioTopology& topo = profile.topology;

    // 1. Resolve UAC 2.0 Clock Sources / Clock Selectors for each streaming AltSetting
    auto bindClocksForStreamList = [&](std::vector<AudioStreamAltSetting>& streams) {
        for (auto& alt : streams) {
            if (alt.version != UacVersion::Uac2) continue;

            uint8_t clockEntityId = 0;
            if (alt.direction == StreamDirection::PlaybackOut) {
                if (const auto* it = findInputTerminal(topo, alt.terminalLink)) {
                    clockEntityId = it->clockSourceId;
                }
            } else {
                if (const auto* ot = findOutputTerminal(topo, alt.terminalLink)) {
                    clockEntityId = ot->clockSourceId;
                }
            }

            if (clockEntityId == 0 && !topo.clockSources.empty()) {
                // Fallback if a non-compliant DAC left bCSourceID = 0
                clockEntityId = topo.clockSources.front().id;
            }

            if (const auto* sel = findClockSelector(topo, clockEntityId)) {
                alt.clockSelectorId = sel->id;
                size_t bestRateCount = 0;
                uint32_t bestMaxRate = 0;
                for (size_t idx = 0; idx < sel->sourceIds.size(); ++idx) {
                    std::set<uint8_t> visited;
                    if (const auto* cs = traceToClockSource(topo, sel->sourceIds[idx], visited)) {
                        uint32_t csMax = cs->supportedRates.empty() ? cs->maxRate : cs->supportedRates.back();
                        bool isBetterPrimary = (alt.primaryClockSourceId == 0) ||
                            (csMax > bestMaxRate) ||
                            (csMax == bestMaxRate && cs->supportedRates.size() > bestRateCount);
                        if (isBetterPrimary) {
                            alt.primaryClockSourceId = cs->id;
                            bestRateCount = cs->supportedRates.size();
                            bestMaxRate = csMax;
                        }
                        if (cs->continuousRange) {
                            alt.continuousRate = true;
                            if (alt.minSampleRate == 0 || cs->minRate < alt.minSampleRate) {
                                alt.minSampleRate = cs->minRate;
                            }
                            if (cs->maxRate > alt.maxSampleRate) {
                                alt.maxSampleRate = cs->maxRate;
                            }
                        }
                        for (uint32_t rate : cs->supportedRates) {
                            alt.sampleRates.push_back(rate);
                            RateClockRoute route{};
                            route.sampleRate = rate;
                            route.clockSourceId = cs->id;
                            route.clockSelectorId = sel->id;
                            route.clockSelectorPin = static_cast<uint8_t>(idx + 1);

                            // Prefer higher-capability ClockSource (e.g. CS#30 over CS#27) on rate overlap
                            auto existing = std::find_if(
                                alt.rateClockRoutes.begin(),
                                alt.rateClockRoutes.end(),
                                [rate](const RateClockRoute& r) { return r.sampleRate == rate; }
                            );
                            if (existing == alt.rateClockRoutes.end()) {
                                alt.rateClockRoutes.push_back(route);
                            } else if (isBetterPrimary) {
                                *existing = route;
                            }
                        }
                    }
                }
                sortUniqueRates(alt.sampleRates);
            } else {
                std::set<uint8_t> visited;
                if (const auto* cs = traceToClockSource(topo, clockEntityId, visited)) {
                    alt.primaryClockSourceId = cs->id;
                    alt.sampleRates = cs->supportedRates;
                    alt.continuousRate = cs->continuousRange;
                    alt.minSampleRate = cs->minRate;
                    alt.maxSampleRate = cs->maxRate;
                    for (uint32_t rate : cs->supportedRates) {
                        RateClockRoute route{};
                        route.sampleRate = rate;
                        route.clockSourceId = cs->id;
                        alt.rateClockRoutes.push_back(route);
                    }
                }
            }
        }
    };

    bindClocksForStreamList(profile.playbackAltSettings);
    bindClocksForStreamList(profile.captureAltSettings);

    // 2. Locate Playback Feature Unit along USB_STREAMING -> ... -> OutputTerminal path
    const FeatureUnitInfo* bestFu = nullptr;
    for (const auto& ot : topo.outputTerminals) {
        if (ot.terminalType == TERMINAL_USB_STREAMING) continue; // Capture path
        std::set<uint8_t> visited;
        std::vector<const FeatureUnitInfo*> pathFUs;
        if (tracePlaybackPathToUsbTerminal(topo, ot.sourceId, visited, pathFUs)) {
            for (const auto* fu : pathFUs) {
                bool hasVol = fu->masterVolume || fu->channelVolume;
                if (!bestFu || (hasVol && !(bestFu->masterVolume || bestFu->channelVolume))) {
                    bestFu = fu;
                }
            }
        }
    }

    if (bestFu) {
        profile.hasPlaybackFeatureUnit = true;
        profile.playbackFeatureUnit = *bestFu;
    }
}

#ifndef HOST_UNIT_TEST

bool UacParser::parseFromLibusbDevice(
    libusb_device_handle* handle,
    uint16_t fallbackVid,
    uint16_t fallbackPid,
    const std::string& fallbackName,
    DacDeviceProfile& outProfile,
    std::string& outError
) {
    if (!handle) {
        outError = "Null libusb_device_handle";
        return false;
    }

    outProfile = DacDeviceProfile{};
    libusb_device* dev = libusb_get_device(handle);
    if (!dev) {
        outError = "libusb_get_device returned null";
        return false;
    }

    libusb_device_descriptor devDesc{};
    int rc = libusb_get_device_descriptor(dev, &devDesc);
    if (rc == 0) {
        outProfile.vendorId = devDesc.idVendor;
        outProfile.productId = devDesc.idProduct;
        outProfile.manufacturer = readUsbStringDescriptor(handle, devDesc.iManufacturer);
        outProfile.productName  = readUsbStringDescriptor(handle, devDesc.iProduct);
        outProfile.serialNumber = readUsbStringDescriptor(handle, devDesc.iSerialNumber);
    } else {
        BP_LOGW("libusb_get_device_descriptor failed (%s), using Java fallback IDs",
                libusb_error_name(rc));
        outProfile.vendorId = fallbackVid;
        outProfile.productId = fallbackPid;
    }

    if (outProfile.productName.empty()) {
        outProfile.productName = fallbackName.empty() ? "USB Audio DAC" : fallbackName;
    }

    outProfile.usbSpeed = libusb_get_device_speed(dev);
    outProfile.usbSpeedName = libusbSpeedToString(outProfile.usbSpeed);

    libusb_config_descriptor* config = nullptr;
    rc = libusb_get_active_config_descriptor(dev, &config);
    if (rc != 0 || !config) {
        BP_LOGW("get_active_config_descriptor failed (%s), trying config index 0",
                libusb_error_name(rc));
        rc = libusb_get_config_descriptor(dev, 0, &config);
    }
    if (rc != 0 || !config) {
        outError = std::string("Failed to read USB config descriptor: ") + libusb_error_name(rc);
        return false;
    }

    std::set<int> audioIfaceSet;

    // Pass 1: Locate AudioControl (0x01/0x01) interface and parse topology
    for (int i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            if (alt.bInterfaceClass != USB_CLASS_AUDIO) continue;
            audioIfaceSet.insert(alt.bInterfaceNumber);

            if (alt.bInterfaceSubClass == SUBCLASS_AUDIOCONTROL) {
                UacVersion ver = UacVersion::Uac1;
                if (alt.bInterfaceProtocol == PROTOCOL_UAC2) {
                    ver = UacVersion::Uac2;
                } else if (alt.bInterfaceProtocol == PROTOCOL_UAC3) {
                    ver = UacVersion::Uac3;
                }
                outProfile.topology.controlInterfaceNumber = alt.bInterfaceNumber;
                outProfile.topology.version = ver;
                outProfile.uacVersion = ver;

                parseAudioControlExtra(alt.extra, alt.extra_length, ver, outProfile.topology);
                if (outProfile.topology.version != UacVersion::Unknown) {
                    outProfile.uacVersion = outProfile.topology.version;
                }
            }
        }
    }

    // Pass 2: Parse all AudioStreaming (0x01/0x02) interfaces and Alternate Settings
    for (int i = 0; i < config->bNumInterfaces; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            if (alt.bInterfaceClass != USB_CLASS_AUDIO ||
                alt.bInterfaceSubClass != SUBCLASS_AUDIOSTREAMING) {
                continue;
            }
            if (alt.bNumEndpoints == 0) {
                // AltSetting 0 is the standard zero-bandwidth idle setting
                continue;
            }

            UacVersion streamVer = outProfile.uacVersion;
            if (alt.bInterfaceProtocol == PROTOCOL_UAC2) {
                streamVer = UacVersion::Uac2;
            } else if (alt.bInterfaceProtocol == PROTOCOL_UAC3) {
                streamVer = UacVersion::Uac3;
            } else if (streamVer == UacVersion::Unknown) {
                streamVer = UacVersion::Uac1;
            }

            // Locate primary Isochronous data endpoint & optional feedback endpoint
            const libusb_endpoint_descriptor* dataEp = nullptr;
            const libusb_endpoint_descriptor* feedbackEp = nullptr;
            bool hasImplicitFb = false;

            for (int e = 0; e < alt.bNumEndpoints; ++e) {
                const libusb_endpoint_descriptor& ep = alt.endpoint[e];
                uint8_t xferType = ep.bmAttributes & EP_TRANSFER_TYPE_MASK;
                if (xferType != EP_TRANSFER_TYPE_ISO) continue;

                uint8_t usageType = (ep.bmAttributes >> EP_USAGE_TYPE_SHIFT) & EP_USAGE_TYPE_MASK;
                if (usageType == EP_USAGE_FEEDBACK) {
                    feedbackEp = &ep;
                } else if (!dataEp) {
                    dataEp = &ep;
                    if (usageType == EP_USAGE_IMPLICIT_FEEDBACK) {
                        hasImplicitFb = true;
                    }
                }
            }

            if (!dataEp) continue;

            AudioStreamAltSetting parsed{};
            parsed.interfaceNumber = alt.bInterfaceNumber;
            parsed.altSetting = alt.bAlternateSetting;
            parsed.version = streamVer;
            parsed.direction = (dataEp->bEndpointAddress & EP_DIR_IN_MASK)
                               ? StreamDirection::CaptureIn
                               : StreamDirection::PlaybackOut;

            parsed.dataEndpointAddr = dataEp->bEndpointAddress;
            parsed.maxPacketSizeRaw = dataEp->wMaxPacketSize;
            uint32_t basePacket = dataEp->wMaxPacketSize & 0x07FFu;
            uint32_t mult = 1u + ((dataEp->wMaxPacketSize >> 11) & 0x03u);
            parsed.maxPacketBytesPerInterval = basePacket * mult;
            parsed.interval = dataEp->bInterval;
            parsed.syncType = static_cast<EndpointSyncType>(
                (dataEp->bmAttributes >> EP_SYNC_TYPE_SHIFT) & EP_SYNC_TYPE_MASK
            );
            parsed.implicitFeedback = hasImplicitFb;

            // Detect Feedback Endpoint: UAC2 sibling EP OR UAC1 bSynchAddress
            if (feedbackEp) {
                parsed.hasFeedbackEndpoint = true;
                parsed.feedbackEndpointAddr = feedbackEp->bEndpointAddress;
                parsed.feedbackMaxPacketSize = feedbackEp->wMaxPacketSize;
                parsed.feedbackInterval = feedbackEp->bInterval;
                parsed.feedbackRefresh = feedbackEp->bRefresh;
            } else if (dataEp->bSynchAddress != 0) {
                parsed.hasFeedbackEndpoint = true;
                parsed.feedbackEndpointAddr = dataEp->bSynchAddress;
                // Find matching sibling endpoint descriptor to extract interval/refresh/maxPacket
                for (int e = 0; e < alt.bNumEndpoints; ++e) {
                    if (alt.endpoint[e].bEndpointAddress == dataEp->bSynchAddress) {
                        parsed.feedbackMaxPacketSize = alt.endpoint[e].wMaxPacketSize;
                        parsed.feedbackInterval = alt.endpoint[e].bInterval;
                        parsed.feedbackRefresh = alt.endpoint[e].bRefresh;
                        break;
                    }
                }
            }

            parseAudioStreamingExtra(alt.extra, alt.extra_length, streamVer, parsed);
            parseAudioEndpointExtra(dataEp->extra, dataEp->extra_length, streamVer, parsed);

            if (parsed.direction == StreamDirection::PlaybackOut) {
                outProfile.playbackAltSettings.push_back(std::move(parsed));
            } else {
                outProfile.captureAltSettings.push_back(std::move(parsed));
            }
        }
    }

    libusb_free_config_descriptor(config);

    outProfile.audioInterfaceNumbers.assign(audioIfaceSet.begin(), audioIfaceSet.end());

    // Pass 3: For UAC 2.0, query Clock Source sample rate tables via control transfers
    if (outProfile.uacVersion == UacVersion::Uac2 &&
        outProfile.topology.controlInterfaceNumber >= 0) {
        for (auto& cs : outProfile.topology.clockSources) {
            queryUac2ClockSourceRates(handle, outProfile.topology.controlInterfaceNumber, cs);
        }
    }

    // Pass 4: Resolve Clock Source/Selector routing and Playback Feature Unit
    resolveTopology(outProfile);

    // Pass 5: Query hardware volume dB range on resolved playback Feature Unit (if present)
    if (outProfile.hasPlaybackFeatureUnit && outProfile.topology.controlInterfaceNumber >= 0) {
        queryFeatureUnitVolumeRange(
            handle,
            outProfile.topology.controlInterfaceNumber,
            outProfile.uacVersion,
            outProfile.playbackFeatureUnit
        );
    }

    if (outProfile.playbackAltSettings.empty()) {
        BP_LOGW("No USB Audio Playback (OUT) streaming alternate settings found!");
    }

    return true;
}

#endif // HOST_UNIT_TEST

std::string UacParser::formatDiagnosticReport(const DacDeviceProfile& profile) {
    std::ostringstream oss;
    char hexBuf[64];

    snprintf(hexBuf, sizeof(hexBuf), "%04X:%04X", profile.vendorId, profile.productId);
    oss << "============================================================\n";
    oss << "USB DAC Profile: " << profile.productName;
    if (!profile.manufacturer.empty()) {
        oss << " (" << profile.manufacturer << ")";
    }
    oss << "\n";
    oss << "  VID:PID       : " << hexBuf << "\n";
    oss << "  USB Speed     : " << profile.usbSpeedName << "\n";
    oss << "  UAC Version   : " << uacVersionToString(profile.uacVersion)
        << " (bcdADC=0x" << std::hex << std::setw(4) << std::setfill('0')
        << profile.topology.bcdAdc << std::dec << ")\n";
    oss << "  AC Interface  : #" << profile.topology.controlInterfaceNumber << "\n";

    // Complete AC Topology: Input Terminals (0x02), Output Terminals (0x03), Units (0x04/0x05/0x07/0x08/0x09/0x0D)
    if (!profile.topology.inputTerminals.empty()) {
        oss << "\n[Input Terminals (0x02)]\n";
        for (const auto& it : profile.topology.inputTerminals) {
            snprintf(hexBuf, sizeof(hexBuf), "0x%04X", it.terminalType);
            oss << "  - IT ID=" << static_cast<int>(it.id)
                << " | Type=" << terminalTypeToString(it.terminalType) << "(" << hexBuf << ")"
                << " | ch=" << static_cast<int>(it.channels)
                << " | ClockID=" << static_cast<int>(it.clockSourceId)
                << " | Assoc=" << static_cast<int>(it.assocTerminal) << "\n";
        }
    }

    if (!profile.topology.outputTerminals.empty()) {
        oss << "[Output Terminals (0x03)]\n";
        for (const auto& ot : profile.topology.outputTerminals) {
            snprintf(hexBuf, sizeof(hexBuf), "0x%04X", ot.terminalType);
            oss << "  - OT ID=" << static_cast<int>(ot.id)
                << " | Type=" << terminalTypeToString(ot.terminalType) << "(" << hexBuf << ")"
                << " | src=" << static_cast<int>(ot.sourceId)
                << " | ClockID=" << static_cast<int>(ot.clockSourceId)
                << " | Assoc=" << static_cast<int>(ot.assocTerminal) << "\n";
        }
    }

    if (!profile.topology.routingUnits.empty()) {
        oss << "[Routing / Processing / Extension Units]\n";
        for (const auto& ru : profile.topology.routingUnits) {
            oss << "  - " << acUnitSubtypeToString(ru.subtype, profile.uacVersion)
                << " ID=" << static_cast<int>(ru.id) << " | Srcs=[";
            for (size_t i = 0; i < ru.sourceIds.size(); ++i) {
                if (i > 0) oss << ", ";
                oss << static_cast<int>(ru.sourceIds[i]);
            }
            oss << "]\n";
        }
    }

    // Clock Topology (UAC2)
    if (!profile.topology.clockSources.empty()) {
        oss << "\n[Clock Sources (0x0A)]\n";
        for (const auto& cs : profile.topology.clockSources) {
            oss << "  - ClockSource ID=" << static_cast<int>(cs.id)
                << " | Attr=0x" << std::hex << std::setw(2) << std::setfill('0')
                << static_cast<int>(cs.attributes) << std::dec
                << " | FreqCtrl=" << (cs.freqWritable ? "RW" : (cs.freqReadable ? "RO" : "None"))
                << " | ValidCtrl=" << (cs.validityReadable ? "Y" : "N")
                << " | Rates=[";
            for (size_t i = 0; i < cs.supportedRates.size(); ++i) {
                if (i > 0) oss << ", ";
                oss << cs.supportedRates[i];
            }
            oss << "]";
            if (cs.continuousRange) {
                oss << " (Continuous " << cs.minRate << "-" << cs.maxRate << " Hz)";
            }
            oss << "\n";
        }
    }

    oss << "[Clock Selectors (0x0B): " << profile.topology.clockSelectors.size() << "]\n";
    for (const auto& sel : profile.topology.clockSelectors) {
        oss << "  - ClockSelector ID=" << static_cast<int>(sel.id)
            << " | bNrInPins=" << sel.sourceIds.size()
            << " | Writable=" << (sel.selectorWritable ? "Yes" : "No")
            << " | baCSourceID=[";
        for (size_t i = 0; i < sel.sourceIds.size(); ++i) {
            if (i > 0) oss << ", ";
            oss << "pin" << (i + 1) << "->ID" << static_cast<int>(sel.sourceIds[i]);
        }
        oss << "]\n";
    }

    if (!profile.topology.clockMultipliers.empty()) {
        oss << "[Clock Multipliers (0x0C)]\n";
        for (const auto& mul : profile.topology.clockMultipliers) {
            oss << "  - ClockMultiplier ID=" << static_cast<int>(mul.id)
                << " | src=" << static_cast<int>(mul.sourceId) << "\n";
        }
    }

    // Playback Feature Unit (Hardware Volume)
    oss << "\n[Hardware Volume / Feature Unit]\n";
    if (profile.hasPlaybackFeatureUnit) {
        const auto& fu = profile.playbackFeatureUnit;
        oss << "  - Playback FeatureUnit ID=" << static_cast<int>(fu.id)
            << " (src=" << static_cast<int>(fu.sourceId)
            << ", ch=" << static_cast<int>(fu.numLogicalChannels) << ")"
            << " | Master(Vol=" << (fu.masterVolume ? "Y" : "N")
            << ", Mute=" << (fu.masterMute ? "Y" : "N") << ")"
            << " | PerCh(Vol=" << (fu.channelVolume ? "Y" : "N")
            << ", Mute=" << (fu.channelMute ? "Y" : "N") << ")\n";
        if (fu.volumeRangeQueried) {
            double minDb = fu.volumeMinQ88 / 256.0;
            double maxDb = fu.volumeMaxQ88 / 256.0;
            double resDb = fu.volumeResQ88 / 256.0;
            oss << "    dB Range: " << std::fixed << std::setprecision(2)
                << minDb << " dB .. " << maxDb << " dB (step " << resDb << " dB)\n";
        }
    } else {
        oss << "  - None detected on playback topology path\n";
    }

    // Playback AudioStreaming Alternate Settings
    oss << "\n[Playback AudioStreaming OUT AltSettings ("
        << profile.playbackAltSettings.size() << ")]\n";
    for (const auto& alt : profile.playbackAltSettings) {
        snprintf(hexBuf, sizeof(hexBuf), "0x%02X", alt.dataEndpointAddr);
        oss << "  - IF#" << alt.interfaceNumber << " Alt#" << alt.altSetting
            << " | " << pcmFormatToString(alt.pcmFormat)
            << " | " << static_cast<int>(alt.channels) << "ch"
            << " (slot=" << static_cast<int>(alt.subslotSize)
            << "B, bits=" << static_cast<int>(alt.bitResolution) << ")\n";
        oss << "    Data EP=" << hexBuf
            << " | Sync=" << syncTypeToString(alt.syncType)
            << " | MaxPkt=" << alt.maxPacketBytesPerInterval << "B"
            << " (raw=0x" << std::hex << alt.maxPacketSizeRaw << std::dec << ")"
            << " | bInterval=" << static_cast<int>(alt.interval);
        if (alt.version == UacVersion::Uac1) {
            oss << " | EpFreqCtrl=" << (alt.uac1SamplingFreqControl ? "Yes" : "No");
        }
        oss << "\n";

        if (alt.hasFeedbackEndpoint) {
            snprintf(hexBuf, sizeof(hexBuf), "0x%02X", alt.feedbackEndpointAddr);
            oss << "    Feedback EP=" << hexBuf
                << " | MaxPkt=" << alt.feedbackMaxPacketSize << "B"
                << " | bInterval=" << static_cast<int>(alt.feedbackInterval)
                << " | bRefresh=" << static_cast<int>(alt.feedbackRefresh) << "\n";
        } else if (alt.implicitFeedback) {
            oss << "    Feedback: Implicit (via Iso IN data endpoint)\n";
        }

        if (alt.version == UacVersion::Uac2) {
            oss << "    ClockRoute: PrimaryCS=" << static_cast<int>(alt.primaryClockSourceId);
            if (alt.clockSelectorId != 0) {
                oss << " (via Selector ID=" << static_cast<int>(alt.clockSelectorId) << ")";
            }
            oss << "\n";
        }

        oss << "    SampleRates (" << alt.sampleRates.size() << "): [";
        for (size_t i = 0; i < alt.sampleRates.size(); ++i) {
            if (i > 0) oss << ", ";
            oss << alt.sampleRates[i];
        }
        oss << "]";
        if (alt.continuousRate) {
            oss << " (Continuous " << alt.minSampleRate << "-" << alt.maxSampleRate << " Hz)";
        }
        oss << "\n";
    }

    if (!profile.captureAltSettings.empty()) {
        oss << "\n[Capture AudioStreaming IN AltSettings: "
            << profile.captureAltSettings.size() << " detected]\n";
    }
    oss << "============================================================";

    return oss.str();
}

std::string UacParser::toJson(const DacDeviceProfile& profile) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"vendorId\":" << profile.vendorId << ",";
    oss << "\"productId\":" << profile.productId << ",";
    oss << "\"manufacturer\":\"" << jsonEscape(profile.manufacturer) << "\",";
    oss << "\"productName\":\"" << jsonEscape(profile.productName) << "\",";
    oss << "\"usbSpeed\":\"" << jsonEscape(profile.usbSpeedName) << "\",";
    oss << "\"uacVersion\":\"" << uacVersionToString(profile.uacVersion) << "\",";
    oss << "\"controlInterface\":" << profile.topology.controlInterfaceNumber << ",";
    oss << "\"hasHardwareVolume\":"
        << ((profile.hasPlaybackFeatureUnit &&
             (profile.playbackFeatureUnit.masterVolume || profile.playbackFeatureUnit.channelVolume))
            ? "true" : "false") << ",";
    oss << "\"featureUnitId\":"
        << (profile.hasPlaybackFeatureUnit ? static_cast<int>(profile.playbackFeatureUnit.id) : -1) << ",";

    if (profile.hasPlaybackFeatureUnit && profile.playbackFeatureUnit.volumeRangeQueried) {
        oss << "\"volumeMinDb\":" << std::fixed << std::setprecision(2)
            << (profile.playbackFeatureUnit.volumeMinQ88 / 256.0) << ",";
        oss << "\"volumeMaxDb\":"
            << (profile.playbackFeatureUnit.volumeMaxQ88 / 256.0) << ",";
    }

    oss << "\"playbackStreams\":[";
    for (size_t i = 0; i < profile.playbackAltSettings.size(); ++i) {
        const auto& alt = profile.playbackAltSettings[i];
        if (i > 0) oss << ",";
        oss << "{";
        oss << "\"interfaceNumber\":" << alt.interfaceNumber << ",";
        oss << "\"altSetting\":" << alt.altSetting << ",";
        oss << "\"format\":\"" << pcmFormatToString(alt.pcmFormat) << "\",";
        oss << "\"isPcm\":" << (alt.isPcm ? "true" : "false") << ",";
        oss << "\"channels\":" << static_cast<int>(alt.channels) << ",";
        oss << "\"subslotSize\":" << static_cast<int>(alt.subslotSize) << ",";
        oss << "\"bitResolution\":" << static_cast<int>(alt.bitResolution) << ",";
        oss << "\"dataEndpoint\":" << static_cast<int>(alt.dataEndpointAddr) << ",";
        oss << "\"maxPacketBytes\":" << alt.maxPacketBytesPerInterval << ",";
        oss << "\"interval\":" << static_cast<int>(alt.interval) << ",";
        oss << "\"syncType\":\"" << syncTypeToString(alt.syncType) << "\",";
        oss << "\"hasFeedback\":" << (alt.hasFeedbackEndpoint ? "true" : "false") << ",";
        oss << "\"feedbackEndpoint\":" << static_cast<int>(alt.feedbackEndpointAddr) << ",";
        oss << "\"clockSourceId\":" << static_cast<int>(alt.primaryClockSourceId) << ",";
        oss << "\"sampleRates\":[";
        for (size_t r = 0; r < alt.sampleRates.size(); ++r) {
            if (r > 0) oss << ",";
            oss << alt.sampleRates[r];
        }
        oss << "]";
        oss << "}";
    }
    oss << "]";
    oss << "}";
    return oss.str();
}

} // namespace bitperfect::uac
