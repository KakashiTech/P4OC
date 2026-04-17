#pragma once

#include <atomic>
#include <string>
#include <vector>
#include <mutex>
#include <cstdint>

namespace sse {

/**
 * Fixed-capacity SPSC ring buffer for raw SSE event data strings.
 * Push is called from the library's SSE thread; drain from the consumer.
 *
 * Same design as pty::OutputBuffer — lock-free push, mutex on drain.
 */
class EventBuffer {
public:
    static constexpr int DEFAULT_CAPACITY = 256;

    explicit EventBuffer(int capacity = DEFAULT_CAPACITY);
    ~EventBuffer() = default;

    EventBuffer(const EventBuffer&) = delete;
    EventBuffer& operator=(const EventBuffer&) = delete;

    // Push raw event JSON string. Returns false if buffer full (event dropped).
    bool push(const char* data, size_t len);
    bool push(const std::string& data);

    // Drain up to `max` events into `out`. Returns count drained.
    int drain(std::vector<std::string>& out, int max = 32);

    size_t size() const { return size_.load(std::memory_order_relaxed); }
    bool empty() const  { return size() == 0; }

    int64_t totalPushed()  const { return totalPushed_.load(std::memory_order_relaxed); }
    int64_t totalDropped() const { return totalDropped_.load(std::memory_order_relaxed); }

    void clear();

private:
    struct Slot {
        std::atomic<bool> ready{false};
        std::string data;
    };

    const int capacity_;
    std::vector<Slot> slots_;
    std::atomic<int> head_{0};
    std::atomic<int> tail_{0};
    std::atomic<size_t> size_{0};
    std::atomic<int64_t> totalPushed_{0};
    std::atomic<int64_t> totalDropped_{0};
    mutable std::mutex drainMutex_;
};

/**
 * Native SSE backoff calculator.
 * Mirrors OpenCodeEventSource.computeRetryDelayMs() logic exactly:
 *   tier = min(errors / 2, 5)
 *   base = min(2000 << tier, 60000)
 *   jittered = base * [0.8, 1.2)
 */
class SseBackoff {
public:
    SseBackoff();

    // Returns next retry delay in ms based on error count.
    int64_t computeDelayMs(int consecutiveErrors);

    void reset();

private:
    uint64_t rngState_;
    uint64_t nextRand();
    int64_t applyJitter(int64_t baseMs);
};

} // namespace sse
