#pragma once

#include <cstdint>
#include <atomic>

namespace memory {

/**
 * Memory pressure levels — mirrors MemoryManager.MemoryPressure.
 */
enum class PressureLevel : int32_t {
    LOW      = 0,
    MEDIUM   = 1,
    HIGH     = 2,
    CRITICAL = 3
};

/**
 * Pressure classifier — pure arithmetic on total/available bytes.
 * Called from the Kotlin monitoring loop; eliminates boxing overhead.
 *
 * Thresholds (match Kotlin exactly):
 *   CRITICAL : available < total * 0.05
 *   HIGH     : available < total * 0.15
 *   MEDIUM   : available < total * 0.30
 *   LOW      : otherwise
 */
class PressureClassifier {
public:
    static PressureLevel classify(int64_t totalBytes, int64_t availableBytes);

    // Hysteresis variant: only escalate if threshold exceeded for `minCycles` consecutive calls.
    PressureLevel classifyWithHysteresis(int64_t totalBytes, int64_t availableBytes,
                                          int minCycles = 2);

    void reset();

private:
    std::atomic<int>            consecutiveCycles_{0};
    std::atomic<PressureLevel>  lastLevel_{PressureLevel::LOW};
};

/**
 * Object pool for fixed-size byte slabs.
 * Pre-allocates a set of arenas to hand out to callers without hitting the allocator.
 *
 * Usage pattern: Chat message text gets copied into a slab; returned to pool on clear.
 * Reduces GC churn on long conversations where many small strings would be created.
 */
class SlabPool {
public:
    static constexpr int DEFAULT_SLAB_SIZE  = 4096;  // 4KB per slab
    static constexpr int DEFAULT_POOL_SIZE  = 64;    // 64 slabs pre-allocated

    SlabPool(int slabSize = DEFAULT_SLAB_SIZE, int poolSize = DEFAULT_POOL_SIZE);
    ~SlabPool();

    SlabPool(const SlabPool&) = delete;
    SlabPool& operator=(const SlabPool&) = delete;

    // Acquire a slab. Returns pointer or nullptr if pool exhausted.
    char* acquire();

    // Return a slab to the pool.
    void  release(char* slab);

    // Bytes copied into this slab (zero-copy metadata).
    int   slabSize() const { return slabSize_; }

    // Stats
    int   available() const;
    int   poolSize()  const { return poolSize_; }
    int64_t totalAcquired() const;
    int64_t totalReleased() const;

private:
    const int slabSize_;
    const int poolSize_;

    // Stack-based free list using atomics
    struct Node {
        char*  data;
        Node*  next;
    };

    std::atomic<Node*>  freeList_{nullptr};
    Node*               nodes_{nullptr};
    char*               slabs_{nullptr};

    std::atomic<int64_t> totalAcquired_{0};
    std::atomic<int64_t> totalReleased_{0};
};

} // namespace memory
