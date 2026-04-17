#pragma once

#include <atomic>
#include <string>
#include <unordered_map>
#include <vector>
#include <mutex>
#include <cstdint>
#include <cstring>

namespace pool {

/**
 * Slot metadata for a single pooled connection.
 * Stores only the tracking data (timestamps, state, stats).
 * The actual WS client lives in Kotlin — we just track its lifecycle.
 */
struct ConnectionSlot {
    char    url[256];
    int64_t createdAt;
    int64_t lastUsed;
    bool    inUse;
    int32_t useCount;
    int32_t id;         // opaque handle back to Kotlin side

    ConnectionSlot()
        : createdAt(0), lastUsed(0), inUse(false), useCount(0), id(-1) {
        url[0] = '\0';
    }
};

struct PoolStats {
    int activeCount;
    int pooledCount;
    int totalCreated;
    int totalEvicted;
    float reuseRate;    // (totalReused / totalCreated) * 100
};

/**
 * Connection registry — tracks metadata for pooled WS connections.
 *
 * Design:
 *  - Hash map keyed by URL, guarded by single mutex (pool operations are rare)
 *  - Health check is a pure computation: age < maxAge && idle < maxIdle
 *  - All atomic ops for stats to avoid mutex in hot read path
 */
class ConnectionRegistry {
public:
    static constexpr int64_t MAX_AGE_MS   = 30000LL;
    static constexpr int64_t MAX_IDLE_MS  = 15000LL;
    static constexpr int      MAX_PER_URL = 5;

    ConnectionRegistry();

    /**
     * Try to find an idle healthy slot for `url`.
     * Returns slot ID if found (slot is now marked inUse), -1 otherwise.
     */
    int acquire(const char* url, int64_t nowMs);

    /**
     * Register a new connection slot.
     * Returns the assigned slot ID.
     */
    int registerSlot(const char* url, int id, int64_t nowMs);

    /**
     * Release a slot back to the pool.
     * Returns true if slot is still healthy and was retained; false if evicted.
     */
    bool release(int slotId, int64_t nowMs);

    /**
     * Evict all slots older than cutoffMs or idle longer than MAX_IDLE_MS.
     * Returns count of evicted slot IDs in `evictedIds` (caller closes them).
     */
    int evictStale(int64_t nowMs, std::vector<int>& evictedIds);

    /**
     * Check if a slot is healthy without acquiring it.
     */
    bool isHealthy(int slotId, int64_t nowMs) const;

    PoolStats getStats() const;

    void clear(std::vector<int>& allIds);

private:
    struct Entry {
        ConnectionSlot slot;
        bool active; // in active map vs pool
    };

    mutable std::mutex mutex_;
    std::unordered_map<int, Entry> slots_;          // slotId → Entry
    std::unordered_map<std::string, std::vector<int>> urlIndex_; // url → slotIds

    std::atomic<int>  nextId_{0};
    std::atomic<int>  totalCreated_{0};
    std::atomic<int>  totalEvicted_{0};
    std::atomic<int>  totalReused_{0};
};

} // namespace pool
