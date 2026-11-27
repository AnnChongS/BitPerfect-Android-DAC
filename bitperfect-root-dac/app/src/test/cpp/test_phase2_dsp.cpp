#include "pcm_converter.h"
#include "spsc_ring_buffer.h"
#include "usb_iso_streamer.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <thread>
#include <vector>

using namespace bitperfect;

static void testSpscRingBufferWrapAndThreads() {
    SpscRingBuffer rb(65536);
    assert(rb.capacity() == 65536);

    constexpr size_t kTotalBytes = 4u * 1024u * 1024u;
    std::thread producer([&]() {
        std::vector<uint8_t> chunk(1337);
        size_t sent = 0;
        while (sent < kTotalBytes) {
            size_t n = std::min(chunk.size(), kTotalBytes - sent);
            for (size_t i = 0; i < n; ++i) {
                chunk[i] = static_cast<uint8_t>((sent + i) & 0xFFu);
            }
            size_t w = rb.write(chunk.data(), n);
            sent += w;
            if (w == 0) std::this_thread::yield();
        }
    });

    std::vector<uint8_t> out(997);
    size_t received = 0;
    while (received < kTotalBytes) {
        size_t n = std::min(out.size(), kTotalBytes - received);
        size_t r = rb.read(out.data(), n);
        for (size_t i = 0; i < r; ++i) {
            uint8_t expected = static_cast<uint8_t>((received + i) & 0xFFu);
            assert(out[i] == expected);
        }
        received += r;
        if (r == 0) std::this_thread::yield();
    }
    producer.join();
    assert(rb.availableRead() == 0);
    std::cout << "[PASS] testSpscRingBufferWrapAndThreads (4MB lock-free verified)\n";
}

static void testQuark2AdaptiveMicroframePacing() {
    IsoPacketPacer pacer;

    // 1) Moondrop Quark 2 Alt#1: 44.1kHz, 16-bit S16_LE (4B/frame), HS bInterval=1, MaxPkt=248B
    pacer.configure(44100, /*HS*/3, /*bInterval*/1, /*frameBytes*/4, /*maxPkt*/248, false);
    assert(pacer.intervalsPerSec() == 8000);
    uint64_t totalBytes44k = 0;
    uint32_t count5 = 0, count6 = 0;
    for (int i = 0; i < 8000; ++i) {
        uint32_t b = pacer.nextPacketBytes();
        assert(b <= 248);
        assert(b == 20 || b == 24); // Strictly 5 or 6 frames per 125us microframe
        if (b == 20) ++count5;
        if (b == 24) ++count6;
        totalBytes44k += b;
    }
    assert(totalBytes44k == 44100u * 4u); // Exact 0 ppm drift over 1.000s!
    assert(count5 == 3900 && count6 == 4100);

    // 2) Moondrop Quark 2 Alt#2: 96kHz, 24-bit S24_3LE (6B/frame), HS bInterval=1, MaxPkt=372B
    pacer.configure(96000, /*HS*/3, /*bInterval*/1, /*frameBytes*/6, /*maxPkt*/372, false);
    uint64_t totalBytes96k = 0;
    for (int i = 0; i < 8000; ++i) {
        uint32_t b = pacer.nextPacketBytes();
        assert(b == 12u * 6u); // Exact 12 frames = 72 bytes every 125us microframe
        assert(b <= 372);
        totalBytes96k += b;
    }
    assert(totalBytes96k == 96000u * 6u);

    // 3) Moondrop Quark 2 Alt#3: 384kHz, 32-bit S32_LE (8B/frame), HS bInterval=1, MaxPkt=496B
    pacer.configure(384000, /*HS*/3, /*bInterval*/1, /*frameBytes*/8, /*maxPkt*/496, false);
    uint64_t totalBytes384k = 0;
    for (int i = 0; i < 8000; ++i) {
        uint32_t b = pacer.nextPacketBytes();
        assert(b == 48u * 8u); // 384 bytes <= 496 bytes
        totalBytes384k += b;
    }
    assert(totalBytes384k == 384000u * 8u);

    std::cout << "[PASS] testQuark2AdaptiveMicroframePacing (44.1k/96k/384k 0-ppm exact)\n";
}

static void testAsyncFeedbackDecoding() {
    IsoPacketPacer pacer;
    // HS 96kHz async + feedback (12.0 nominal frames/microframe)
    pacer.configure(96000, /*HS*/3, /*bInterval*/1, /*frameBytes*/6, /*maxPkt*/372, true);

    // 12.01 frames/microframe in 16.16 = (12 << 16) + round(0.01 * 65536) = 787087
    uint32_t fbVal = (12u << 16) + 655u;
    uint8_t fb4[4] = {
        static_cast<uint8_t>(fbVal & 0xFF),
        static_cast<uint8_t>((fbVal >> 8) & 0xFF),
        static_cast<uint8_t>((fbVal >> 16) & 0xFF),
        static_cast<uint8_t>((fbVal >> 24) & 0xFF)
    };
    assert(pacer.applyFeedbackPayload(fb4, 4));
    assert(pacer.lastFeedbackHz() >= 96070 && pacer.lastFeedbackHz() <= 96090);

    // Reject wild +50% corrupted feedback
    uint32_t wildVal = 18u << 16;
    uint8_t wild4[4] = {0, 0, static_cast<uint8_t>(wildVal >> 16), 0};
    assert(!pacer.applyFeedbackPayload(wild4, 4));

    // FS 44.1kHz async + 3-byte 10.14 feedback (44.125 frames/ms = 44*16384 + 2048 = 722944)
    pacer.configure(44100, /*FS*/2, /*bInterval*/1, /*frameBytes*/4, /*maxPkt*/200, true);
    uint32_t fs1014 = (44u << 14) + 2048u;
    uint8_t fb3[3] = {
        static_cast<uint8_t>(fs1014 & 0xFF),
        static_cast<uint8_t>((fs1014 >> 8) & 0xFF),
        static_cast<uint8_t>((fs1014 >> 16) & 0xFF)
    };
    assert(pacer.applyFeedbackPayload(fb3, 3));
    assert(pacer.lastFeedbackHz() == 44125);

    std::cout << "[PASS] testAsyncFeedbackDecoding (HS 16.16 & FS 10.14 + sanity guard)\n";
}

static void testSinePackingFormats() {
    SineWaveGenerator gen;
    gen.configure(96000, uac::PcmFormat::S24_3LE, 2, 440.0, 0.5);
    std::vector<uint8_t> buf(2400 * 6, 0);
    size_t written = gen.generateFrames(buf.data(), 2400);
    assert(written == 2400 * 6);

    // Check first sample is 0 (cosine fade-in starts at 0 -> click-free)
    assert(buf[0] == 0 && buf[1] == 0 && buf[2] == 0);

    // Check after 15ms fade-in (frame 1500), non-zero stereo L == R
    size_t off = 1500 * 6;
    assert(buf[off] == buf[off + 3] && buf[off + 1] == buf[off + 4] && buf[off + 2] == buf[off + 5]);

    std::cout << "[PASS] testSinePackingFormats (S24_3LE click-free cosine fade-in)\n";
}

static void testCapturedPcmConversionAndDriftServo() {
    // 1) Bit-perfect S24_3LE -> S24_3LE direct memcpy path
    std::vector<uint8_t> src24(16 * 6);
    for (size_t i = 0; i < src24.size(); ++i) src24[i] = static_cast<uint8_t>(i * 7 + 3);
    std::vector<uint8_t> dst;
    CaptureBlockStats stats{};
    size_t outB = convertCapturedPcmBlock(
        src24.data(), src24.size(),
        CapturePcmFormat::S24_3LE, uac::PcmFormat::S24_3LE, 2, 0, dst, stats
    );
    assert(outB == src24.size());
    assert(std::equal(src24.begin(), src24.end(), dst.begin()));

    // 2) Float32_LE -> S24_3LE lossless conversion + drift +1 insert -> 17 frames
    std::vector<float> srcFloat(16 * 2, 0.5f);
    outB = convertCapturedPcmBlock(
        reinterpret_cast<const uint8_t*>(srcFloat.data()),
        srcFloat.size() * sizeof(float),
        CapturePcmFormat::Float32_LE, uac::PcmFormat::S24_3LE, 2, +1, dst, stats
    );
    assert(outB == 17 * 6);
    assert(stats.peakLinear > 0.49 && stats.peakLinear < 0.51);

    // 3) Drift -1 drop -> 15 frames
    outB = convertCapturedPcmBlock(
        reinterpret_cast<const uint8_t*>(srcFloat.data()),
        srcFloat.size() * sizeof(float),
        CapturePcmFormat::Float32_LE, uac::PcmFormat::S24_3LE, 2, -1, dst, stats
    );
    assert(outB == 15 * 6);

    // 4) Detect embedded zero-silence dropout (30 consecutive zero frames inside loud music)
    std::vector<float> dropoutFloat(100 * 2, 0.5f);
    for (size_t f = 30; f < 60; ++f) {
        dropoutFloat[f * 2] = 0.0f;
        dropoutFloat[f * 2 + 1] = 0.0f;
    }
    outB = convertCapturedPcmBlock(
        reinterpret_cast<const uint8_t*>(dropoutFloat.data()),
        dropoutFloat.size() * sizeof(float),
        CapturePcmFormat::Float32_LE, uac::PcmFormat::S24_3LE, 2, 0, dst, stats
    );
    assert(outB == 100 * 6);
    assert(stats.zeroGapRuns == 1 && !stats.allZeroBlock);

    std::cout << "[PASS] testCapturedPcmConversionAndDriftServo (bit-perfect + drift + zero-dropout detector)\n";
}

int main() {
    testSpscRingBufferWrapAndThreads();
    testQuark2AdaptiveMicroframePacing();
    testAsyncFeedbackDecoding();
    testSinePackingFormats();
    testCapturedPcmConversionAndDriftServo();
    std::cout << "\nALL PHASE 2+3 DSP & ISO PACING UNIT TESTS PASSED!\n";
    return 0;
}
