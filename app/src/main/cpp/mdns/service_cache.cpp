#include "mdns/service_cache.h"
#include <cstring>

namespace mdns {

static void strncpySafe(char* dst, const char* src, size_t n) {
    std::strncpy(dst, src, n - 1);
    dst[n - 1] = '\0';
}

void ServiceCache::upsert(const NativeService& svc) {
    std::lock_guard<std::mutex> lock(mutex_);
    map_[std::string(svc.name)] = svc;
}

bool ServiceCache::remove(const char* name) {
    std::lock_guard<std::mutex> lock(mutex_);
    return map_.erase(std::string(name)) > 0;
}

std::vector<NativeService> ServiceCache::getAll() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<NativeService> result;
    result.reserve(map_.size());
    for (const auto& kv : map_) {
        result.push_back(kv.second);
    }
    return result;
}

void ServiceCache::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    map_.clear();
}

int ServiceCache::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int>(map_.size());
}

} // namespace mdns
