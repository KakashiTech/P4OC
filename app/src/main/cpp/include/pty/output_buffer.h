#pragma once

#include <atomic>
#include <string>
#include <vector>
#include <functional>
#include <mutex>
#include <cstdint>

namespace pty {

/**
 * Lock-free output buffer for PTY terminal text.
 * Batches incoming frames and emits them with minimal overhead.
 *
 * Design:
 *  - Single-producer (WS thread) / single-consumer (UI thread) ring buffer
 *  - Atomic head/tail for lock-free push; mutex only on drain
 *  - Zero-copy: stores string_views into a pre-allocated slab when possible
 */
class OutputBuffer {
public:
    static constexpr int DEFAULT_CAPACITY = 1024; // frames

    explicit OutputBuffer(int capacity = DEFAULT_CAPACITY);
    ~OutputBuffer();

    OutputBuffer(const OutputBuffer&) = delete;
    OutputBuffer& operator=(const OutputBuffer&) = delete;

    // Hot path — called from WS thread for every incoming frame
    bool push(const char* data, size_t len);
    bool push(const std::string& text);

    // Drain up to `max` frames into output vector. Called from consumer.
    int drain(std::vector<std::string>& out, int max = 64);

    // Stats
    size_t size() const { return size_.load(std::memory_order_relaxed); }
    bool empty() const { return size() == 0; }
    int64_t totalReceived() const { return totalReceived_.load(std::memory_order_relaxed); }
    int64_t totalDropped() const { return totalDropped_.load(std::memory_order_relaxed); }

    void clear();

private:
    struct Slot {
        std::atomic<bool> ready{false};
        std::string data;
    };

    const int capacity_;
    std::vector<Slot> slots_;

    std::atomic<int> head_{0}; // producer writes here
    std::atomic<int> tail_{0}; // consumer reads here
    std::atomic<size_t> size_{0};

    std::atomic<int64_t> totalReceived_{0};
    std::atomic<int64_t> totalDropped_{0};

    mutable std::mutex drainMutex_;
};

} // namespace pty
