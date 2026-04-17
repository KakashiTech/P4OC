#include "pool/connection_registry.h"
#include <algorithm>
#include <cstring>
#include <cstdio>

namespace pool {

ConnectionRegistry::ConnectionRegistry() = default;

int ConnectionRegistry::acquire(const char* url, int64_t nowMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = urlIndex_.find(std::string(url));
    if (it == urlIndex_.end()) return -1;

    for (int sid : it->second) {
        auto& e = slots_[sid];
        if (e.slot.inUse) continue;
        // Health check
        int64_t age  = nowMs - e.slot.createdAt;
        int64_t idle = nowMs - e.slot.lastUsed;
        if (age >= MAX_AGE_MS || idle >= MAX_IDLE_MS) continue;

        e.slot.inUse = true;
        e.slot.lastUsed = nowMs;
        e.slot.useCount++;
        e.active = true;
        totalReused_.fetch_add(1, std::memory_order_relaxed);
        return sid;
    }
    return -1;
}

int ConnectionRegistry::registerSlot(const char* url, int id, int64_t nowMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    int sid = nextId_.fetch_add(1, std::memory_order_relaxed);

    Entry e;
    std::strncpy(e.slot.url, url, sizeof(e.slot.url) - 1);
    e.slot.url[sizeof(e.slot.url) - 1] = '\0';
    e.slot.createdAt = nowMs;
    e.slot.lastUsed  = nowMs;
    e.slot.inUse     = true;
    e.slot.useCount  = 1;
    e.slot.id        = id;
    e.active         = true;

    slots_[sid] = e;
    urlIndex_[std::string(url)].push_back(sid);
    totalCreated_.fetch_add(1, std::memory_order_relaxed);
    return sid;
}

bool ConnectionRegistry::release(int slotId, int64_t nowMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = slots_.find(slotId);
    if (it == slots_.end()) return false;

    Entry& e = it->second;
    e.slot.inUse    = false;
    e.slot.lastUsed = nowMs;
    e.active        = false;

    // Check pool size limit
    const std::string url(e.slot.url);
    auto& ids = urlIndex_[url];
    int poolCount = 0;
    for (int sid : ids) {
        if (!slots_[sid].slot.inUse) poolCount++;
    }

    if (poolCount > MAX_PER_URL) {
        // Evict this slot (over limit)
        ids.erase(std::remove(ids.begin(), ids.end(), slotId), ids.end());
        slots_.erase(it);
        totalEvicted_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    return true;
}

int ConnectionRegistry::evictStale(int64_t nowMs, std::vector<int>& evictedIds) {
    std::lock_guard<std::mutex> lock(mutex_);
    int count = 0;

    std::vector<int> toRemove;
    for (auto& [sid, e] : slots_) {
        if (e.slot.inUse) continue;
        int64_t age  = nowMs - e.slot.createdAt;
        int64_t idle = nowMs - e.slot.lastUsed;
        if (age >= MAX_AGE_MS || idle >= MAX_IDLE_MS) {
            evictedIds.push_back(e.slot.id);
            toRemove.push_back(sid);
            count++;
        }
    }

    for (int sid : toRemove) {
        const std::string url(slots_[sid].slot.url);
        auto& ids = urlIndex_[url];
        ids.erase(std::remove(ids.begin(), ids.end(), sid), ids.end());
        if (ids.empty()) urlIndex_.erase(url);
        slots_.erase(sid);
        totalEvicted_.fetch_add(1, std::memory_order_relaxed);
    }
    return count;
}

bool ConnectionRegistry::isHealthy(int slotId, int64_t nowMs) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = slots_.find(slotId);
    if (it == slots_.end()) return false;
    const auto& slot = it->second.slot;
    int64_t age  = nowMs - slot.createdAt;
    int64_t idle = nowMs - slot.lastUsed;
    return age < MAX_AGE_MS && idle < MAX_IDLE_MS;
}

PoolStats ConnectionRegistry::getStats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    int active = 0, pooled = 0;
    for (const auto& [sid, e] : slots_) {
        if (e.slot.inUse) active++; else pooled++;
    }
    int created  = totalCreated_.load(std::memory_order_relaxed);
    int evicted  = totalEvicted_.load(std::memory_order_relaxed);
    int reused   = totalReused_.load(std::memory_order_relaxed);
    float rate   = (created > 0) ? (float(reused) / float(created)) * 100.0f : 0.0f;
    return {active, pooled, created, evicted, rate};
}

void ConnectionRegistry::clear(std::vector<int>& allIds) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& [sid, e] : slots_) {
        allIds.push_back(e.slot.id);
    }
    slots_.clear();
    urlIndex_.clear();
}

} // namespace pool
