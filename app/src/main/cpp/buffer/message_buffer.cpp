#include "buffer/message_buffer.h"
#include <algorithm>
#include <cstring>
#include <vector>
#include <chrono>

namespace buffer {

NativeMessageBuffer::NativeMessageBuffer(int maxSize)
    : maxSize_(maxSize) {
    stats_.maxSize = maxSize;
}

NativeMessageBuffer::~NativeMessageBuffer() {
    clear();
}

bool NativeMessageBuffer::add(const NativeMessage& msg) {
    evictIfNeeded();
    queue_.push(msg);
    stats_.currentSize.fetch_add(1, std::memory_order_relaxed);
    stats_.totalProcessed.fetch_add(1, std::memory_order_relaxed);
    return true;
}

bool NativeMessageBuffer::remove(const char* messageId) {
    // Drain queue into temp, skip matching id, re-push rest
    std::lock_guard<std::mutex> lock(evictMutex_);
    std::vector<NativeMessage> snapshot;
    snapshot.reserve(static_cast<size_t>(stats_.currentSize.load()));

    NativeMessage msg;
    bool found = false;
    while (queue_.pop(msg)) {
        if (!found && std::strncmp(msg.id, messageId, sizeof(msg.id)) == 0) {
            found = true;
            stats_.currentSize.fetch_sub(1, std::memory_order_relaxed);
        } else {
            snapshot.push_back(std::move(msg));
        }
    }
    for (auto& m : snapshot) queue_.push(std::move(m));
    return found;
}

std::vector<NativeMessage> NativeMessageBuffer::getAll() const {
    std::lock_guard<std::mutex> lock(evictMutex_);
    // Snapshot: drain → collect → re-push
    std::vector<NativeMessage> result;
    result.reserve(static_cast<size_t>(stats_.currentSize.load()));

    NativeMessage msg;
    while (const_cast<LockFreeQueue<NativeMessage>&>(queue_).pop(msg)) {
        result.push_back(msg);
    }
    for (auto& m : result) {
        const_cast<LockFreeQueue<NativeMessage>&>(queue_).push(m);
    }
    return result;
}

std::vector<NativeMessage> NativeMessageBuffer::getLast(int limit) const {
    auto all = getAll();
    if (static_cast<int>(all.size()) <= limit) return all;
    return std::vector<NativeMessage>(all.end() - limit, all.end());
}

NativeMessage* NativeMessageBuffer::findById(const char* messageId) {
    // Not safe to return ptr into a lock-free queue — intentionally returns nullptr.
    // Callers should use getLast() + search in the snapshot.
    (void)messageId;
    return nullptr;
}

int NativeMessageBuffer::evictOldest(int count) {
    std::lock_guard<std::mutex> lock(evictMutex_);
    int evicted = 0;
    NativeMessage msg;
    while (evicted < count && queue_.pop(msg)) {
        stats_.currentSize.fetch_sub(1, std::memory_order_relaxed);
        stats_.evictionCount.fetch_add(1, std::memory_order_relaxed);
        evicted++;
    }
    return evicted;
}

int NativeMessageBuffer::evictByAge(int64_t cutoffTimestampMs) {
    std::lock_guard<std::mutex> lock(evictMutex_);
    std::vector<NativeMessage> keep;
    keep.reserve(static_cast<size_t>(stats_.currentSize.load()));

    int evicted = 0;
    NativeMessage msg;
    while (queue_.pop(msg)) {
        if (msg.createdAt < cutoffTimestampMs) {
            stats_.currentSize.fetch_sub(1, std::memory_order_relaxed);
            stats_.evictionCount.fetch_add(1, std::memory_order_relaxed);
            evicted++;
        } else {
            keep.push_back(std::move(msg));
        }
    }
    for (auto& m : keep) queue_.push(std::move(m));
    return evicted;
}

void NativeMessageBuffer::clear() {
    NativeMessage msg;
    while (queue_.pop(msg)) {}
    stats_.currentSize.store(0, std::memory_order_relaxed);
}

int NativeMessageBuffer::computePriority(const NativeMessage& msg) const {
    int priority = 0;
    auto now = std::chrono::duration_cast<std::chrono::hours>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    int64_t ageHours = now - (msg.createdAt / 3600000LL);
    priority += static_cast<int>(std::max(0LL, 100LL - ageHours));
    if (msg.messageType == 0) priority += 15; // User messages
    if (msg.hasTools) priority += 10;
    return priority;
}

void NativeMessageBuffer::evictIfNeeded() {
    int current = static_cast<int>(stats_.currentSize.load(std::memory_order_relaxed));
    if (current < maxSize_) return;

    // Evict oldest 10% — O(n) but rare
    int toEvict = maxSize_ / 10;
    evictOldest(toEvict);
}

} // namespace buffer
