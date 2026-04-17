#include "sse/event_buffer.h"
#include <algorithm>
#include <chrono>
#include <cstring>

namespace sse {

// ── EventBuffer ───────────────────────────────────────────────────────────────

EventBuffer::EventBuffer(int capacity)
    : capacity_(capacity), slots_(capacity) {}

bool EventBuffer::push(const char* data, size_t len) {
    totalPushed_.fetch_add(1, std::memory_order_relaxed);

    if (size_.load(std::memory_order_acquire) >= static_cast<size_t>(capacity_)) {
        totalDropped_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

    int head = head_.load(std::memory_order_relaxed);
    Slot& slot = slots_[head];
    slot.data.assign(data, len);
    slot.ready.store(true, std::memory_order_release);

    head_.store((head + 1) % capacity_, std::memory_order_release);
    size_.fetch_add(1, std::memory_order_release);
    return true;
}

bool EventBuffer::push(const std::string& data) {
    return push(data.data(), data.size());
}

int EventBuffer::drain(std::vector<std::string>& out, int max) {
    std::lock_guard<std::mutex> lock(drainMutex_);
    int drained = 0;
    int tail = tail_.load(std::memory_order_relaxed);

    while (drained < max && size_.load(std::memory_order_acquire) > 0) {
        Slot& slot = slots_[tail];
        if (!slot.ready.load(std::memory_order_acquire)) break;

        out.push_back(std::move(slot.data));
        slot.ready.store(false, std::memory_order_release);

        tail = (tail + 1) % capacity_;
        tail_.store(tail, std::memory_order_release);
        size_.fetch_sub(1, std::memory_order_release);
        drained++;
    }
    return drained;
}

void EventBuffer::clear() {
    std::lock_guard<std::mutex> lock(drainMutex_);
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
    size_.store(0, std::memory_order_relaxed);
    for (auto& slot : slots_) {
        slot.ready.store(false, std::memory_order_relaxed);
        slot.data.clear();
    }
}

// ── SseBackoff ────────────────────────────────────────────────────────────────

SseBackoff::SseBackoff() {
    rngState_ = static_cast<uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
    if (rngState_ == 0) rngState_ = 0xBEEFCAFE12345678ULL;
}

int64_t SseBackoff::computeDelayMs(int consecutiveErrors) {
    int errors = consecutiveErrors < 0 ? 0 : consecutiveErrors;
    int tier = errors / 2;
    if (tier > 5) tier = 5;
    int64_t base = (2000LL << tier);
    if (base > 60000LL) base = 60000LL;
    return applyJitter(base);
}

void SseBackoff::reset() {
    // Stateless — nothing to reset
}

uint64_t SseBackoff::nextRand() {
    rngState_ ^= rngState_ << 13;
    rngState_ ^= rngState_ >> 7;
    rngState_ ^= rngState_ << 17;
    return rngState_;
}

int64_t SseBackoff::applyJitter(int64_t baseMs) {
    if (baseMs <= 0) return 0;
    // ±20% jitter: range [base*0.8, base*1.2)
    int64_t minMs = static_cast<int64_t>(baseMs * 0.8);
    int64_t range = static_cast<int64_t>(baseMs * 0.4); // 1.2 - 0.8 = 0.4
    if (range == 0) return minMs;
    int64_t offset = static_cast<int64_t>(nextRand() % static_cast<uint64_t>(range));
    return minMs + offset;
}

} // namespace sse
