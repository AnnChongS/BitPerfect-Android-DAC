#include "uac_constants.h"
#include "uac_parser.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

using namespace bitperfect::uac;

static void pushLe16(std::vector<uint8_t>& v, uint16_t val) {
    v.push_back(static_cast<uint8_t>(val & 0xFF));
    v.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
}

static void pushLe24(std::vector<uint8_t>& v, uint32_t val) {
    v.push_back(static_cast<uint8_t>(val & 0xFF));
    v.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
    v.push_back(static_cast<uint8_t>((val >> 16) & 0xFF));
}

static void pushLe32(std::vector<uint8_t>& v, uint32_t val) {
    v.push_back(static_cast<uint8_t>(val & 0xFF));
    v.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
    v.push_back(static_cast<uint8_t>((val >> 16) & 0xFF));
    v.push_back(static_cast<uint8_t>((val >> 24) & 0xFF));
}

static void testUac1DiscreteAndFeatureUnit() {
    // Build synthetic UAC 1.0 AudioControl extra buffer:
    // HEADER + INPUT_TERMINAL(USB Streaming ID=1) + FEATURE_UNIT(ID=2, src=1) + OUTPUT_TERMINAL(HP ID=3, src=2)
    std::vector<uint8_t> ac;
    // AC HEADER (9 bytes, 1 streaming interface #1)
    ac.insert(ac.end(), {9, DT_CS_INTERFACE, AC_HEADER, 0x00, 0x01, 0x28, 0x00, 1, 1});
    // AC INPUT_TERMINAL (12 bytes): ID=1, USB Streaming (0x0101), 2ch
    ac.insert(ac.end(), {12, DT_CS_INTERFACE, AC_INPUT_TERMINAL, 1, 0x01, 0x01, 0, 2, 0x03, 0x00, 0, 0});
    // AC FEATURE_UNIT (10 bytes): ID=2, src=1, bControlSize=1, master=Mute|Vol(0x03), L=Vol(0x02), R=Vol(0x02), iFeature=0
    ac.insert(ac.end(), {10, DT_CS_INTERFACE, AC_FEATURE_UNIT, 2, 1, 1, 0x03, 0x02, 0x02, 0});
    // AC OUTPUT_TERMINAL (9 bytes): ID=3, Headphones (0x0302), src=2
    ac.insert(ac.end(), {9, DT_CS_INTERFACE, AC_OUTPUT_TERMINAL, 3, 0x02, 0x03, 0, 2, 0});

    DacDeviceProfile profile{};
    profile.vendorId = 0x1234;
    profile.productId = 0x5678;
    profile.productName = "Test UAC1 DAC";
    profile.uacVersion = UacVersion::Uac1;
    profile.topology.controlInterfaceNumber = 0;

    UacParser::parseAudioControlExtra(ac.data(), static_cast<int>(ac.size()), UacVersion::Uac1, profile.topology);

    assert(profile.topology.bcdAdc == 0x0100);
    assert(profile.topology.inputTerminals.size() == 1);
    assert(profile.topology.outputTerminals.size() == 1);
    assert(profile.topology.featureUnits.size() == 1);
    assert(profile.topology.featureUnits[0].masterMute);
    assert(profile.topology.featureUnits[0].masterVolume);
    assert(profile.topology.featureUnits[0].channelVolume);
    assert(profile.topology.featureUnits[0].numLogicalChannels == 2);

    // Build synthetic UAC 1.0 AudioStreaming extra buffer:
    // AS_GENERAL (7B, terminalLink=1, wFormatTag=PCM 0x0001) + FORMAT_TYPE_I (S24_3LE, 4 discrete rates)
    std::vector<uint8_t> as;
    as.insert(as.end(), {7, DT_CS_INTERFACE, AS_GENERAL, 1, 1, 0x01, 0x00});
    // FORMAT_TYPE_I: bLength = 8 + 4*3 = 20, 2ch, subslot=3, bits=24, bSamFreqType=4
    as.insert(as.end(), {20, DT_CS_INTERFACE, AS_FORMAT_TYPE, FORMAT_TYPE_I, 2, 3, 24, 4});
    pushLe24(as, 44100);
    pushLe24(as, 48000);
    pushLe24(as, 88200);
    pushLe24(as, 96000);

    std::vector<uint8_t> epExtra = {7, DT_CS_ENDPOINT, EP_GENERAL, 0x01, 0, 0, 0};

    AudioStreamAltSetting alt{};
    alt.interfaceNumber = 1;
    alt.altSetting = 1;
    alt.version = UacVersion::Uac1;
    alt.direction = StreamDirection::PlaybackOut;
    alt.dataEndpointAddr = 0x01;
    alt.syncType = EndpointSyncType::Asynchronous;
    alt.hasFeedbackEndpoint = true;
    alt.feedbackEndpointAddr = 0x81;

    UacParser::parseAudioStreamingExtra(as.data(), static_cast<int>(as.size()), UacVersion::Uac1, alt);
    UacParser::parseAudioEndpointExtra(epExtra.data(), static_cast<int>(epExtra.size()), UacVersion::Uac1, alt);

    assert(alt.isPcm);
    assert(alt.pcmFormat == PcmFormat::S24_3LE);
    assert(alt.channels == 2);
    assert(alt.uac1SamplingFreqControl);
    assert((alt.sampleRates == std::vector<uint32_t>{44100, 48000, 88200, 96000}));

    profile.playbackAltSettings.push_back(alt);
    UacParser::resolveTopology(profile);

    assert(profile.hasPlaybackFeatureUnit);
    assert(profile.playbackFeatureUnit.id == 2);

    std::cout << "[PASS] testUac1DiscreteAndFeatureUnit\n";
}

static void testUac1ContinuousRates() {
    std::vector<uint8_t> as;
    as.insert(as.end(), {7, DT_CS_INTERFACE, AS_GENERAL, 1, 0, 0x01, 0x00});
    // Continuous range: 44100 .. 192000 Hz, S16_LE
    as.insert(as.end(), {14, DT_CS_INTERFACE, AS_FORMAT_TYPE, FORMAT_TYPE_I, 2, 2, 16, 0});
    pushLe24(as, 44100);
    pushLe24(as, 192000);

    AudioStreamAltSetting alt{};
    alt.version = UacVersion::Uac1;
    UacParser::parseAudioStreamingExtra(as.data(), static_cast<int>(as.size()), UacVersion::Uac1, alt);

    assert(alt.pcmFormat == PcmFormat::S16_LE);
    assert(alt.continuousRate);
    assert(alt.minSampleRate == 44100);
    assert(alt.maxSampleRate == 192000);
    assert((alt.sampleRates == std::vector<uint32_t>{44100, 48000, 88200, 96000, 176400, 192000}));

    std::cout << "[PASS] testUac1ContinuousRates\n";
}

static void testUac2DualClockSelectorAndTopology() {
    // UAC 2.0 AudioControl:
    // HEADER + CS#40 (44.1k family) + CS#41 (48k family) + CX#42 (pins=[40, 41])
    // + IT#1 (USB Streaming, bCSourceID=42) + FU#5 (src=1, Master Vol+Mute RW) + OT#3 (HP, src=5)
    std::vector<uint8_t> ac;
    // AC HEADER (9 bytes, bcdADC=0x0200)
    ac.insert(ac.end(), {9, DT_CS_INTERFACE, AC_HEADER, 0x00, 0x02, 0x01, 0x50, 0x00, 0x00});
    // CLOCK_SOURCE #40 (8 bytes): internal programmable (0x03), freq RW (0x03) | valid RO (0x04) = 0x07
    ac.insert(ac.end(), {8, DT_CS_INTERFACE, AC_CLOCK_SOURCE, 40, 0x03, 0x07, 0, 0});
    // CLOCK_SOURCE #41 (8 bytes)
    ac.insert(ac.end(), {8, DT_CS_INTERFACE, AC_CLOCK_SOURCE, 41, 0x03, 0x07, 0, 0});
    // CLOCK_SELECTOR #42 (9 bytes): 2 pins -> [40, 41], bmControls=0x03 (RW)
    ac.insert(ac.end(), {9, DT_CS_INTERFACE, AC_CLOCK_SELECTOR, 42, 2, 40, 41, 0x03, 0});
    // INPUT_TERMINAL #1 (17 bytes): USB Streaming (0x0101), bCSourceID=42, 2ch
    ac.insert(ac.end(), {17, DT_CS_INTERFACE, AC_INPUT_TERMINAL, 1, 0x01, 0x01, 0, 42, 2, 0x03, 0, 0, 0, 0, 0, 0, 0});
    // FEATURE_UNIT #5 (18 bytes = 5 + 3*4 + 1): src=1, Master=MuteRW(0x03)|VolRW(0x0C)=0x0F, ch1=0, ch2=0
    ac.insert(ac.end(), {18, DT_CS_INTERFACE, AC_FEATURE_UNIT, 5, 1});
    pushLe32(ac, 0x0000000Fu); // Master: Mute RW + Volume RW
    pushLe32(ac, 0x00000000u); // Ch 1
    pushLe32(ac, 0x00000000u); // Ch 2
    ac.push_back(0);           // iFeature
    // OUTPUT_TERMINAL #3 (12 bytes): Headphones (0x0302), src=5, bCSourceID=42
    ac.insert(ac.end(), {12, DT_CS_INTERFACE, AC_OUTPUT_TERMINAL, 3, 0x02, 0x03, 0, 5, 42, 0, 0, 0});

    DacDeviceProfile profile{};
    profile.vendorId = 0x2972;
    profile.productId = 0x0047;
    profile.productName = "Mock Dual-Oscillator UAC2 DAC";
    profile.usbSpeedName = "High Speed (480 Mbps)";
    profile.uacVersion = UacVersion::Uac2;
    profile.topology.controlInterfaceNumber = 0;

    UacParser::parseAudioControlExtra(ac.data(), static_cast<int>(ac.size()), UacVersion::Uac2, profile.topology);

    assert(profile.topology.clockSources.size() == 2);
    assert(profile.topology.clockSelectors.size() == 1);
    assert(profile.topology.featureUnits.size() == 1);
    assert(profile.topology.featureUnits[0].masterVolume);
    assert(profile.topology.featureUnits[0].masterMute);

    // Simulate UAC2 RANGE response for CS#40 (44.1k, 88.2k, 176.4k)
    std::vector<uint8_t> range40;
    pushLe16(range40, 3);
    for (uint32_t r : {44100u, 88200u, 176400u}) {
        pushLe32(range40, r);
        pushLe32(range40, r);
        pushLe32(range40, 0);
    }
    bool ok40 = UacParser::parseUac2ClockRangeResponse(
        range40.data(), static_cast<int>(range40.size()),
        profile.topology.clockSources[0].supportedRates,
        profile.topology.clockSources[0].continuousRange,
        profile.topology.clockSources[0].minRate,
        profile.topology.clockSources[0].maxRate
    );
    assert(ok40);

    // Simulate UAC2 RANGE response for CS#41 (48k, 96k, 192k)
    std::vector<uint8_t> range41;
    pushLe16(range41, 3);
    for (uint32_t r : {48000u, 96000u, 192000u}) {
        pushLe32(range41, r);
        pushLe32(range41, r);
        pushLe32(range41, 0);
    }
    bool ok41 = UacParser::parseUac2ClockRangeResponse(
        range41.data(), static_cast<int>(range41.size()),
        profile.topology.clockSources[1].supportedRates,
        profile.topology.clockSources[1].continuousRange,
        profile.topology.clockSources[1].minRate,
        profile.topology.clockSources[1].maxRate
    );
    assert(ok41);

    // UAC 2.0 AudioStreaming extra: AS_GENERAL (16B) + FORMAT_TYPE (6B, S32_LE)
    std::vector<uint8_t> as;
    as.insert(as.end(), {16, DT_CS_INTERFACE, AS_GENERAL, 1, 0, FORMAT_TYPE_I});
    pushLe32(as, UAC2_FORMAT_PCM);
    as.insert(as.end(), {2, 0x03, 0, 0, 0, 0}); // 2ch, FL|FR
    as.insert(as.end(), {6, DT_CS_INTERFACE, AS_FORMAT_TYPE, FORMAT_TYPE_I, 4, 32});

    AudioStreamAltSetting alt{};
    alt.interfaceNumber = 1;
    alt.altSetting = 1;
    alt.version = UacVersion::Uac2;
    alt.direction = StreamDirection::PlaybackOut;
    alt.dataEndpointAddr = 0x01;
    alt.maxPacketSizeRaw = 0x0200;
    alt.maxPacketBytesPerInterval = 512;
    alt.interval = 1;
    alt.syncType = EndpointSyncType::Asynchronous;
    alt.hasFeedbackEndpoint = true;
    alt.feedbackEndpointAddr = 0x81;
    alt.feedbackMaxPacketSize = 4;

    UacParser::parseAudioStreamingExtra(as.data(), static_cast<int>(as.size()), UacVersion::Uac2, alt);
    assert(alt.pcmFormat == PcmFormat::S32_LE);
    assert(alt.channels == 2);

    profile.playbackAltSettings.push_back(alt);
    UacParser::resolveTopology(profile);

    assert(profile.hasPlaybackFeatureUnit);
    assert(profile.playbackFeatureUnit.id == 5);
    const auto& resolvedAlt = profile.playbackAltSettings[0];
    assert(resolvedAlt.clockSelectorId == 42);
    assert((resolvedAlt.sampleRates == std::vector<uint32_t>{44100, 48000, 88200, 96000, 176400, 192000}));

    bool found441Route = false;
    bool found96Route = false;
    for (const auto& route : resolvedAlt.rateClockRoutes) {
        if (route.sampleRate == 44100 && route.clockSourceId == 40 && route.clockSelectorPin == 1) {
            found441Route = true;
        }
        if (route.sampleRate == 96000 && route.clockSourceId == 41 && route.clockSelectorPin == 2) {
            found96Route = true;
        }
    }
    assert(found441Route && found96Route);

    std::string report = UacParser::formatDiagnosticReport(profile);
    std::string json = UacParser::toJson(profile);
    assert(report.find("UAC 2.0") != std::string::npos);
    assert(report.find("S32_LE") != std::string::npos);
    assert(json.find("\"featureUnitId\":5") != std::string::npos);

    std::cout << "[PASS] testUac2DualClockSelectorAndTopology\n";
    std::cout << "\nSample Diagnostic Report Output:\n" << report << "\n";
}

static void testMalformedDescriptorsDoNotCrash() {
    uint8_t zeroLen[] = {0, DT_CS_INTERFACE, AC_HEADER, 0};
    AudioTopology topo{};
    UacParser::parseAudioControlExtra(zeroLen, sizeof(zeroLen), UacVersion::Uac2, topo);

    uint8_t truncated[] = {20, DT_CS_INTERFACE, AS_GENERAL, 1, 2};
    AudioStreamAltSetting alt{};
    UacParser::parseAudioStreamingExtra(truncated, sizeof(truncated), UacVersion::Uac2, alt);

    std::vector<uint32_t> rates;
    bool cont = false;
    uint32_t minR = 0, maxR = 0;
    uint8_t badRange[] = {5, 0, 1, 2};
    assert(!UacParser::parseUac2ClockRangeResponse(badRange, sizeof(badRange), rates, cont, minR, maxR));

    std::cout << "[PASS] testMalformedDescriptorsDoNotCrash\n";
}

int main() {
    testUac1DiscreteAndFeatureUnit();
    testUac1ContinuousRates();
    testUac2DualClockSelectorAndTopology();
    testMalformedDescriptorsDoNotCrash();
    std::cout << "\nALL UAC1/UAC2 PARSER UNIT TESTS PASSED!\n";
    return 0;
}
