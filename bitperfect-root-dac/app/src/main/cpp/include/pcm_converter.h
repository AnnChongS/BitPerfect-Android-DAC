#pragma once

#include "uac_types.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace bitperfect {

// Wire formats coming from Android AudioRecord (AudioPlaybackCapture)
enum class CapturePcmFormat : int {
    S16_LE     = 1, // AudioFormat.ENCODING_PCM_16BIT
    S24_3LE    = 2, // AudioFormat.ENCODING_PCM_24BIT_PACKED
    S32_LE     = 3, // AudioFormat.ENCODING_PCM_32BIT
    Float32_LE = 4  // AudioFormat.ENCODING_PCM_FLOAT
};

inline size_t pcmBytesPerSample(uac::PcmFormat fmt) {
    switch (fmt) {
        case uac::PcmFormat::S16_LE:     return 2;
        case uac::PcmFormat::S24_3LE:    return 3;
        case uac::PcmFormat::S24_LE_32:  return 4;
        case uac::PcmFormat::S32_LE:     return 4;
        case uac::PcmFormat::Float32_LE: return 4;
        default:                         return 0;
    }
}

inline size_t captureBytesPerSample(CapturePcmFormat fmt) {
    switch (fmt) {
        case CapturePcmFormat::S16_LE:     return 2;
        case CapturePcmFormat::S24_3LE:    return 3;
        case CapturePcmFormat::S32_LE:     return 4;
        case CapturePcmFormat::Float32_LE: return 4;
        default:                           return 0;
    }
}

inline size_t pcmFrameBytes(uac::PcmFormat fmt, uint8_t channels) {
    return pcmBytesPerSample(fmt) * static_cast<size_t>(channels);
}

inline bool formatsDirectlyCompatible(CapturePcmFormat src, uac::PcmFormat dst) {
    return (src == CapturePcmFormat::S16_LE && dst == uac::PcmFormat::S16_LE) ||
           (src == CapturePcmFormat::S24_3LE && dst == uac::PcmFormat::S24_3LE) ||
           (src == CapturePcmFormat::S32_LE && dst == uac::PcmFormat::S32_LE) ||
           (src == CapturePcmFormat::Float32_LE && dst == uac::PcmFormat::Float32_LE);
}

/**
 * Unpacks one sample from Android AudioRecord wire format into canonical MSB-aligned int32_t.
 */
inline int32_t unpackCaptureSampleToInt32(const uint8_t* src, CapturePcmFormat fmt) {
    switch (fmt) {
        case CapturePcmFormat::S16_LE: {
            uint16_t u = static_cast<uint16_t>(src[0]) |
                         (static_cast<uint16_t>(src[1]) << 8);
            return static_cast<int32_t>(static_cast<int16_t>(u)) << 16;
        }
        case CapturePcmFormat::S24_3LE: {
            // Place 24-bit LE into top 24 bits of signed 32-bit int (MSB-aligned, bit-exact)
            uint32_t u = (static_cast<uint32_t>(src[0]) << 8) |
                         (static_cast<uint32_t>(src[1]) << 16) |
                         (static_cast<uint32_t>(src[2]) << 24);
            return static_cast<int32_t>(u);
        }
        case CapturePcmFormat::S32_LE: {
            uint32_t u = static_cast<uint32_t>(src[0]) |
                         (static_cast<uint32_t>(src[1]) << 8) |
                         (static_cast<uint32_t>(src[2]) << 16) |
                         (static_cast<uint32_t>(src[3]) << 24);
            return static_cast<int32_t>(u);
        }
        case CapturePcmFormat::Float32_LE: {
            float f = 0.0f;
            std::memcpy(&f, src, sizeof(float));
            if (!std::isfinite(f)) return 0;
            double clamped = std::clamp(static_cast<double>(f), -1.0, 1.0);
            int64_t s = static_cast<int64_t>(std::llrint(clamped * 2147483647.0));
            s = std::clamp<int64_t>(s, -2147483648LL, 2147483647LL);
            return static_cast<int32_t>(s);
        }
        default:
            return 0;
    }
}

/**
 * Packs a normalized sample in [-1.0, +1.0] into the target DAC wire format
 * (little-endian, MSB-aligned per UAC 1.0/2.0 specification).
 */
inline void packNormalizedSample(double v, uac::PcmFormat fmt, uint8_t* dst) {
    v = std::clamp(v, -1.0, 1.0);
    switch (fmt) {
        case uac::PcmFormat::S16_LE: {
            int32_t s = static_cast<int32_t>(std::lrint(v * 32767.0));
            s = std::clamp(s, -32768, 32767);
            uint16_t u = static_cast<uint16_t>(static_cast<int16_t>(s));
            dst[0] = static_cast<uint8_t>(u & 0xFFu);
            dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            break;
        }
        case uac::PcmFormat::S24_3LE: {
            int32_t s = static_cast<int32_t>(std::lrint(v * 8388607.0));
            s = std::clamp(s, -8388608, 8388607);
            uint32_t u = static_cast<uint32_t>(s);
            dst[0] = static_cast<uint8_t>(u & 0xFFu);
            dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            break;
        }
        case uac::PcmFormat::S24_LE_32: {
            // UAC 1.0 / 2.0: 24-bit sample inside 32-bit subslot is MSB-aligned (LSBs zeroed)
            int32_t s = static_cast<int32_t>(std::lrint(v * 8388607.0));
            s = std::clamp(s, -8388608, 8388607);
            uint32_t u = static_cast<uint32_t>(s) << 8;
            dst[0] = static_cast<uint8_t>(u & 0xFFu);
            dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            dst[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
            break;
        }
        case uac::PcmFormat::S32_LE: {
            int64_t s = static_cast<int64_t>(std::llrint(v * 2147483647.0));
            s = std::clamp<int64_t>(s, -2147483648LL, 2147483647LL);
            uint32_t u = static_cast<uint32_t>(static_cast<int32_t>(s));
            dst[0] = static_cast<uint8_t>(u & 0xFFu);
            dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            dst[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
            break;
        }
        case uac::PcmFormat::Float32_LE: {
            float f = static_cast<float>(v);
            std::memcpy(dst, &f, sizeof(float));
            break;
        }
        default:
            break;
    }
}

/**
 * Packs canonical 32-bit signed integer PCM (MSB-aligned) into target DAC wire format
 * with zero bit loss.
 */
inline void packInt32Sample(int32_t s32, uac::PcmFormat fmt, uint8_t* dst) {
    uint32_t u = static_cast<uint32_t>(s32);
    switch (fmt) {
        case uac::PcmFormat::S16_LE: {
            uint16_t s16 = static_cast<uint16_t>(u >> 16);
            dst[0] = static_cast<uint8_t>(s16 & 0xFFu);
            dst[1] = static_cast<uint8_t>((s16 >> 8) & 0xFFu);
            break;
        }
        case uac::PcmFormat::S24_3LE: {
            dst[0] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            dst[1] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            dst[2] = static_cast<uint8_t>((u >> 24) & 0xFFu);
            break;
        }
        case uac::PcmFormat::S24_LE_32: {
            dst[0] = 0x00u;
            dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            dst[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
            break;
        }
        case uac::PcmFormat::S32_LE: {
            dst[0] = static_cast<uint8_t>(u & 0xFFu);
            dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            dst[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
            break;
        }
        case uac::PcmFormat::Float32_LE: {
            float f = static_cast<float>(static_cast<double>(s32) / 2147483648.0);
            std::memcpy(dst, &f, sizeof(float));
            break;
        }
        default:
            break;
    }
}

struct CaptureBlockStats {
    double peakLinear = 0.0;
    uint32_t zeroGapRuns = 0;     // Runs of >= 24 consecutive exact-zero frames inside this block
    uint32_t zeroFramesTotal = 0; // Total exact-zero frames in this block
    bool allZeroBlock = false;
};

/**
 * Converts a captured stereo/multi-channel PCM block into target DAC wire format.
 *
 * driftAdjustFrames:
 *   0  -> 100% Bit-Perfect (direct memcpy when wire formats match!)
 *  +1  -> Smoothly inserts 1 midpoint-interpolated frame in the middle of the block (underrun guard)
 *  -1  -> Smoothly drops 1 frame by averaging adjacent midpoint pair (overflow/latency guard)
 *
 * Also computes normalized peak level and detects embedded zero-silence dropout runs
 * (e.g. Android r_submix MonoPipe memset(0) gaps).
 */
inline size_t convertCapturedPcmBlock(
    const uint8_t* src,
    size_t srcBytes,
    CapturePcmFormat srcFmt,
    uac::PcmFormat dstFmt,
    uint8_t channels,
    int driftAdjustFrames,
    std::vector<uint8_t>& dstScratch,
    CaptureBlockStats& outStats
) {
    outStats = CaptureBlockStats{};
    const size_t srcBps = captureBytesPerSample(srcFmt);
    const size_t dstBps = pcmBytesPerSample(dstFmt);
    if (!src || srcBps == 0 || dstBps == 0 || channels == 0) return 0;

    const size_t srcFrameBytes = srcBps * channels;
    const size_t dstFrameBytes = dstBps * channels;
    const size_t numFrames = srcBytes / srcFrameBytes;
    if (numFrames == 0) return 0;

    // Require >= 4 frames to apply smooth +/-1 frame drift adjustment
    if (numFrames < 4) {
        driftAdjustFrames = 0;
    }
    driftAdjustFrames = std::clamp(driftAdjustFrames, -1, 1);

    const size_t outFrames = static_cast<size_t>(static_cast<ptrdiff_t>(numFrames) + driftAdjustFrames);
    const size_t outBytes = outFrames * dstFrameBytes;
    if (dstScratch.size() < outBytes) {
        dstScratch.resize(outBytes);
    }

    // Dedicated single-pass Float32_LE -> S24_3LE / S32_LE fast path (primary 192kHz Apple Music path!)
    // Computes peakLinear AND packs UAC2 little-endian samples in ONE tight pass with zero double libm overhead.
    if (driftAdjustFrames == 0 &&
        srcFmt == CapturePcmFormat::Float32_LE &&
        (dstFmt == uac::PcmFormat::S24_3LE || dstFmt == uac::PcmFormat::S32_LE)) {
        const size_t totalSamples = numFrames * channels;
        const uint8_t* sPtr = src;
        uint8_t* dPtr = dstScratch.data();
        float maxAbsFloat = 0.0f;
        uint32_t nonZeroSamples = 0;

        if (dstFmt == uac::PcmFormat::S24_3LE) {
            for (size_t i = 0; i < totalSamples; ++i) {
                float f = 0.0f;
                std::memcpy(&f, sPtr, sizeof(float));
                sPtr += 4;
                int32_t s24 = 0;
                if (std::isfinite(f) && f != 0.0f) {
                    ++nonZeroSamples;
                    float af = std::fabs(f);
                    if (af > maxAbsFloat) maxAbsFloat = af;
                    float clamped = std::clamp(f, -1.0f, 1.0f);
                    s24 = static_cast<int32_t>(std::lrintf(clamped * 8388607.0f));
                    s24 = std::clamp(s24, -8388608, 8388607);
                }
                uint32_t u = static_cast<uint32_t>(s24);
                dPtr[0] = static_cast<uint8_t>(u & 0xFFu);
                dPtr[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
                dPtr[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
                dPtr += 3;
            }
        } else {
            for (size_t i = 0; i < totalSamples; ++i) {
                float f = 0.0f;
                std::memcpy(&f, sPtr, sizeof(float));
                sPtr += 4;
                int32_t s32 = 0;
                if (std::isfinite(f) && f != 0.0f) {
                    ++nonZeroSamples;
                    float af = std::fabs(f);
                    if (af > maxAbsFloat) maxAbsFloat = af;
                    double clamped = std::clamp(static_cast<double>(f), -1.0, 1.0);
                    int64_t s64 = static_cast<int64_t>(std::llrint(clamped * 2147483647.0));
                    s32 = static_cast<int32_t>(std::clamp<int64_t>(s64, -2147483648LL, 2147483647LL));
                }
                uint32_t u = static_cast<uint32_t>(s32);
                dPtr[0] = static_cast<uint8_t>(u & 0xFFu);
                dPtr[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
                dPtr[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
                dPtr[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
                dPtr += 4;
            }
        }

        outStats.peakLinear = std::min(1.0, static_cast<double>(maxAbsFloat));
        outStats.allZeroBlock = (nonZeroSamples == 0);
        return outBytes;
    }

    // Scan peak level + embedded zero-silence runs for remaining formats / fallback r_submix mode
    int32_t maxAbs = 0;
    uint32_t consecutiveZeros = 0;
    bool inZeroRun = false;
    for (size_t f = 0; f < numFrames; ++f) {
        const uint8_t* fp = src + f * srcFrameBytes;
        bool frameZero = true;
        for (uint8_t ch = 0; ch < channels; ++ch) {
            int32_t s = unpackCaptureSampleToInt32(fp + ch * srcBps, srcFmt);
            if (s != 0) frameZero = false;
            int32_t a = (s == INT32_MIN) ? INT32_MAX : std::abs(s);
            if (a > maxAbs) maxAbs = a;
        }
        if (frameZero) {
            ++outStats.zeroFramesTotal;
            ++consecutiveZeros;
            if (consecutiveZeros == 24u) {
                ++outStats.zeroGapRuns;
                inZeroRun = true;
            }
        } else {
            consecutiveZeros = 0;
            inZeroRun = false;
        }
    }
    (void)inZeroRun;
    outStats.peakLinear = static_cast<double>(maxAbs) / 2147483647.0;
    outStats.allZeroBlock = (outStats.zeroFramesTotal == numFrames);
    if (outStats.allZeroBlock) {
        // Pure silence block (e.g. paused track) is tracked separately from mid-block dropouts
        outStats.zeroGapRuns = 0;
    }

    // Fast Bit-Perfect zero-modification memcpy path when formats match and no drift adjustment
    if (driftAdjustFrames == 0 && formatsDirectlyCompatible(srcFmt, dstFmt)) {
        std::memcpy(dstScratch.data(), src, outBytes);
        return outBytes;
    }

    const size_t midFrame = numFrames / 2u;
    uint8_t* dstPtr = dstScratch.data();

    for (size_t f = 0; f < numFrames; ++f) {
        const uint8_t* curFramePtr = src + f * srcFrameBytes;

        if (driftAdjustFrames == -1 && f == midFrame) {
            // Smoothly merge frames [midFrame] and [midFrame + 1] into one midpoint frame
            const uint8_t* nextFramePtr = src + (f + 1u) * srcFrameBytes;
            for (uint8_t ch = 0; ch < channels; ++ch) {
                int64_t s0 = unpackCaptureSampleToInt32(curFramePtr + ch * srcBps, srcFmt);
                int64_t s1 = unpackCaptureSampleToInt32(nextFramePtr + ch * srcBps, srcFmt);
                int32_t avg = static_cast<int32_t>((s0 + s1) / 2);
                packInt32Sample(avg, dstFmt, dstPtr);
                dstPtr += dstBps;
            }
            ++f; // Skip merged second frame
            continue;
        }

        // Write current frame bit-exact
        for (uint8_t ch = 0; ch < channels; ++ch) {
            int32_t s = unpackCaptureSampleToInt32(curFramePtr + ch * srcBps, srcFmt);
            packInt32Sample(s, dstFmt, dstPtr);
            dstPtr += dstBps;
        }

        if (driftAdjustFrames == 1 && f == midFrame) {
            // Insert one smoothly interpolated frame between [midFrame] and [midFrame + 1]
            const uint8_t* nextFramePtr = src + (f + 1u) * srcFrameBytes;
            for (uint8_t ch = 0; ch < channels; ++ch) {
                int64_t s0 = unpackCaptureSampleToInt32(curFramePtr + ch * srcBps, srcFmt);
                int64_t s1 = unpackCaptureSampleToInt32(nextFramePtr + ch * srcBps, srcFmt);
                int32_t interp = static_cast<int32_t>((s0 + s1) / 2);
                packInt32Sample(interp, dstFmt, dstPtr);
                dstPtr += dstBps;
            }
        }
    }

    return static_cast<size_t>(dstPtr - dstScratch.data());
}

/**
 * Click-free double-precision 440Hz reference sine oscillator with smooth cosine fade-in.
 * Default level: -24 dBFS (~0.063 amplitude) so it is completely ear-safe even at 0.00 dB DAC gain.
 */
class SineWaveGenerator {
public:
    static constexpr double kTwoPi = 6.28318530717958647692;

    void configure(uint32_t sampleRate, uac::PcmFormat fmt, uint8_t channels,
                   double freqHz = 440.0, double amplitude = 0.0631) {
        sampleRate_ = sampleRate > 0 ? sampleRate : 44100u;
        format_ = fmt;
        channels_ = channels > 0 ? channels : 2u;
        freqHz_ = freqHz;
        amplitude_ = std::clamp(amplitude, 0.0, 0.95);
        phaseInc_ = kTwoPi * freqHz_ / static_cast<double>(sampleRate_);
        phase_ = 0.0;
        // 15ms smooth cosine fade-in prevents electrical click on stream start / rate switch
        fadeInTotalFrames_ = std::max<uint32_t>(1u, (sampleRate_ * 15u) / 1000u);
        fadeInFrame_ = 0;
    }

    size_t generateFrames(uint8_t* dst, size_t numFrames) {
        const size_t bps = pcmBytesPerSample(format_);
        if (!dst || bps == 0 || numFrames == 0) return 0;

        uint8_t* out = dst;
        for (size_t f = 0; f < numFrames; ++f) {
            double env = 1.0;
            if (fadeInFrame_ < fadeInTotalFrames_) {
                double t = static_cast<double>(fadeInFrame_) / static_cast<double>(fadeInTotalFrames_);
                env = 0.5 * (1.0 - std::cos(3.14159265358979323846 * t));
                ++fadeInFrame_;
            }
            double sample = std::sin(phase_) * amplitude_ * env;
            phase_ += phaseInc_;
            if (phase_ >= kTwoPi) {
                phase_ -= kTwoPi;
            }
            for (uint8_t ch = 0; ch < channels_; ++ch) {
                packNormalizedSample(sample, format_, out);
                out += bps;
            }
        }
        return static_cast<size_t>(out - dst);
    }

private:
    uint32_t sampleRate_ = 44100;
    uac::PcmFormat format_ = uac::PcmFormat::S16_LE;
    uint8_t channels_ = 2;
    double freqHz_ = 440.0;
    double amplitude_ = 0.0631; // -24 dBFS
    double phase_ = 0.0;
    double phaseInc_ = 0.0;
    uint32_t fadeInTotalFrames_ = 661;
    uint32_t fadeInFrame_ = 0;
};

} // namespace bitperfect
