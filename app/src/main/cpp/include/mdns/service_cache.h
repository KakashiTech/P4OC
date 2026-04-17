#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <cstdint>

namespace mdns {

struct NativeService {
    char name[128];
    char host[64];
    int  port;
    char url[256];

    NativeService() : port(0) {
        name[0] = host[0] = url[0] = '\0';
    }
};

/**
 * Thread-safe service cache for discovered mDNS/sweep servers.
 * upsert() is O(1) amortized via hash map; getAll() returns a snapshot.
 */
class ServiceCache {
public:
    ServiceCache() = default;

    // Insert or update a service entry (keyed by name).
    void upsert(const NativeService& svc);

    // Remove by service name. Returns true if found.
    bool remove(const char* name);

    // Snapshot of all current services.
    std::vector<NativeService> getAll() const;

    void clear();

    int size() const;

private:
    mutable std::mutex mutex_;
    std::unordered_map<std::string, NativeService> map_;
};

} // namespace mdns
