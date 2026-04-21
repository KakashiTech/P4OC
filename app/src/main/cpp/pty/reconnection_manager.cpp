#include "pty/reconnection_manager.h"
#include <chrono>
#include <algorithm>

namespace pty {

constexpr int64_t ReconnectionManager::DELAYS_MS[MAX_ATTEMPTS];

ReconnectionManager::ReconnectionManager() {
    // Seed xorshift with current time
    rngState_ = static_cast<uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count()
    );
    if (rngState_ == 0) rngState_ = 0xDEADBEEFCAFEBABEULL;
}

void ReconnectionManager::onConnected() {
    attempts_.store(0, std::memory_order_relaxed);
}

int64_t ReconnectionManager::nextDelayMs() {
    int current = attempts_.load(std::memory_order_relaxed);
    if (current >= MAX_ATTEMPTS) return -1;

    int idx = std::min(current, MAX_ATTEMPTS - 1);
    int64_t base = DELAYS_MS[idx];
    attempts_.fetch_add(1, std::memory_order_relaxed);
    return applyJitter(base);
}

bool ReconnectionManager::shouldReconnect() const {
    return attempts_.load(std::memory_order_relaxed) < MAX_ATTEMPTS;
}

void ReconnectionManager::reset() {
    attempts_.store(0, std::memory_order_relaxed);
}

uint64_t ReconnectionManager::nextRand() {
    // xorshift64
    rngState_ ^= rngState_ << 13;
    rngState_ ^= rngState_ >> 7;
    rngState_ ^= rngState_ << 17;
    return rngState_;
}

int64_t ReconnectionManager::applyJitter(int64_t baseMs) {
    if (baseMs <= 0) return 0;
    // ±20% jitter: range = [base*0.8, base*1.2]
    double ratio = 0.2;
    int64_t range = static_cast<int64_t>(baseMs * ratio * 2);
    if (range == 0) return baseMs;
    int64_t offset = static_cast<int64_t>(nextRand() % static_cast<uint64_t>(range));
    int64_t min = static_cast<int64_t>(baseMs * (1.0 - ratio));
    return min + offset;
}

} // namespace pty
