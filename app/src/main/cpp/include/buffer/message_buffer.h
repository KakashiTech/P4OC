#pragma once

#include <atomic>
#include <string>
#include <vector>
#include <functional>
#include <cstdint>
#include <mutex>
#include "lock_free_queue.h"

namespace buffer {

/**
 * Flat message representation for the native buffer.
 * All data stored as primitives to avoid JNI allocations in the hot path.
 */
struct NativeMessage {
    // Identity
    char id[64];
    char sessionId[64];
    int64_t createdAt;
    int64_t completedAt;    // 0 = not set

    // Content hashes / metadata (only what we need for eviction/priority)
    int messageType;        // 0 = User, 1 = Assistant
    int priority;           // computed once on insert
    bool hasTools;
    bool hasSummary;

    // Token cost (for priority scoring)
    int tokenInput;
    int tokenOutput;

    NativeMessage() : createdAt(0), completedAt(0),
                      messageType(0), priority(0),
                      hasTools(false), hasSummary(false),
                      tokenInput(0), tokenOutput(0) {
        id[0] = '\0';
        sessionId[0] = '\0';
    }
};

/**
 * Buffer statistics (updated atomically).
 */
struct BufferStats {
    std::atomic<int64_t> currentSize{0};
    int64_t maxSize{0};
    std::atomic<int64_t> totalProcessed{0};
    std::atomic<int64_t> evictionCount{0};

    BufferStats() = default;
    BufferStats(const BufferStats&) = delete;
    BufferStats& operator=(const BufferStats&) = delete;

    float utilization() const {
        if (maxSize == 0) return 0.0f;
        return static_cast<float>(currentSize.load(std::memory_order_relaxed)) / maxSize;
    }
};

/**
 * High-performance native message buffer.
 *
 * Design:
 *  - Lock-free push/pop for the hot path (add/remove single message)
 *  - Lightweight std::mutex only for bulk eviction (rare, cold path)
 *  - O(1) eviction of oldest messages (front of queue)
 *  - Priority-based eviction uses a snapshot sort under mutex
 */
class NativeMessageBuffer {
public:
    explicit NativeMessageBuffer(int maxSize = 1000);
    ~NativeMessageBuffer();

    // Non-copyable
    NativeMessageBuffer(const NativeMessageBuffer&) = delete;
    NativeMessageBuffer& operator=(const NativeMessageBuffer&) = delete;

    // Hot path — lock-free
    bool add(const NativeMessage& msg);
    bool remove(const char* messageId);

    // Cold path — snapshot under mutex
    std::vector<NativeMessage> getAll() const;
    std::vector<NativeMessage> getLast(int limit) const;
    NativeMessage* findById(const char* messageId);

    // Eviction
    int evictOldest(int count);
    int evictByAge(int64_t cutoffTimestampMs);

    void clear();

    const BufferStats& stats() const { return stats_; }
    int capacity() const { return maxSize_; }

private:
    int computePriority(const NativeMessage& msg) const;
    void evictIfNeeded();

    const int maxSize_;
    LockFreeQueue<NativeMessage> queue_;
    BufferStats stats_;

    // Mutex only for bulk eviction path
    mutable std::mutex evictMutex_;
};

} // namespace buffer
