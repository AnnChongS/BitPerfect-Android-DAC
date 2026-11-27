#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace bitperfect {

/**
 * Wait-free Single-Producer Single-Consumer (SPSC) byte ring buffer.
 * Capacity is rounded up to a power of two for single-instruction bitmask indexing.
 * Head (write) and tail (read) indices are 64-byte cache-line separated to prevent
 * false sharing on ARM64 big.LITTLE cores.
 */
class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t requestedCapacityBytes = 2u * 1024u * 1024u) {
        resize(requestedCapacityBytes);
    }

    void resize(size_t requestedCapacityBytes) {
        size_t cap = 65536u;
        while (cap < requestedCapacityBytes && cap < (64u * 1024u * 1024u)) {
            cap <<= 1u;
        }
        buffer_.assign(cap, 0);
        capacity_ = cap;
        mask_ = cap - 1u;
        reset();
    }

    // Call only when producer and consumer are quiescent (stopped).
    void reset() {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
        underrunCount_.store(0, std::memory_order_relaxed);
        overrunCount_.store(0, std::memory_order_relaxed);
    }

    size_t capacity() const { return capacity_; }

    size_t availableRead() const {
        const size_t h = head_.load(std::memory_order_acquire);
        const size_t t = tail_.load(std::memory_order_relaxed);
        return h - t;
    }

    size_t availableWrite() const {
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        return capacity_ - (h - t);
    }

    // Producer-only
    size_t write(const uint8_t* src, size_t bytes) {
        if (!src || bytes == 0) return 0;

        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        const size_t freeSpace = capacity_ - (h - t);
        const size_t toWrite = std::min(bytes, freeSpace);

        if (toWrite < bytes) {
            overrunCount_.fetch_add(1, std::memory_order_relaxed);
        }
        if (toWrite == 0) return 0;

        const size_t writePos = h & mask_;
        const size_t firstPart = std::min(toWrite, capacity_ - writePos);
        std::memcpy(buffer_.data() + writePos, src, firstPart);
        if (toWrite > firstPart) {
            std::memcpy(buffer_.data(), src + firstPart, toWrite - firstPart);
        }

        head_.store(h + toWrite, std::memory_order_release);
        return toWrite;
    }

    // Consumer-only
    size_t read(uint8_t* dst, size_t bytes) {
        if (!dst || bytes == 0) return 0;

        const size_t h = head_.load(std::memory_order_acquire);
        const size_t t = tail_.load(std::memory_order_relaxed);
        const size_t avail = h - t;
        const size_t toRead = std::min(bytes, avail);

        if (toRead < bytes) {
            underrunCount_.fetch_add(1, std::memory_order_relaxed);
        }
        if (toRead == 0) return 0;

        const size_t readPos = t & mask_;
        const size_t firstPart = std::min(toRead, capacity_ - readPos);
        std::memcpy(dst, buffer_.data() + readPos, firstPart);
        if (toRead > firstPart) {
            std::memcpy(dst + firstPart, buffer_.data(), toRead - firstPart);
        }

        tail_.store(t + toRead, std::memory_order_release);
        return toRead;
    }

    uint64_t underrunCount() const { return underrunCount_.load(std::memory_order_relaxed); }
    uint64_t overrunCount() const { return overrunCount_.load(std::memory_order_relaxed); }

private:
    std::vector<uint8_t> buffer_;
    size_t capacity_ = 0;
    size_t mask_ = 0;

    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
    alignas(64) std::atomic<uint64_t> underrunCount_{0};
    alignas(64) std::atomic<uint64_t> overrunCount_{0};
};

} // namespace bitperfect
