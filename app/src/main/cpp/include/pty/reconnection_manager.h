#pragma once

#include <atomic>
#include <cstdint>
#include <functional>

namespace pty {

/**
 * Native reconnection manager with exponential backoff + jitter.
 * Tracks attempt count and computes next delay in native code.
 * Scheduling is still done in Kotlin (coroutine delay) — this only
 * computes the delay and manages state transitions.
 */
class ReconnectionManager {
public:
    static constexpr int MAX_ATTEMPTS = 5;

    // Backoff table in ms: 1s, 2s, 4s, 8s, 15s
    static constexpr int64_t DELAYS_MS[MAX_ATTEMPTS] = {
        1000, 2000, 4000, 8000, 15000
    };

    ReconnectionManager();

    // Called when a connection is established — resets counter
    void onConnected();

    // Returns delay in ms for next reconnect, or -1 if max attempts reached
    int64_t nextDelayMs();

    // Returns current attempt count
    int attempts() const { return attempts_.load(std::memory_order_relaxed); }

    // Check if we should still attempt reconnection
    bool shouldReconnect() const;

    void reset();

private:
    std::atomic<int> attempts_{0};

    // xorshift64 for fast jitter without stdlib random overhead
    uint64_t rngState_;
    uint64_t nextRand();

    // Apply ±20% jitter to a base delay
    int64_t applyJitter(int64_t baseMs);
};

} // namespace pty
