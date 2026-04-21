#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace mdns {

/**
 * Fast IPv4 utility functions — no allocation, no JVM overhead.
 * Used heavily during subnet sweep (called 512+ times per scan).
 */

// Converts a dotted-decimal string "a.b.c.d" to packed uint32 (big-endian).
// Returns 0 on parse failure.
inline uint32_t ipv4ToInt(const char* dotted) {
    uint32_t result = 0;
    int octet = 0, shift = 24;
    const char* p = dotted;
    while (*p) {
        if (*p == '.') {
            result |= (octet & 0xFF) << shift;
            shift -= 8;
            octet = 0;
        } else if (*p >= '0' && *p <= '9') {
            octet = octet * 10 + (*p - '0');
        } else {
            return 0;
        }
        ++p;
    }
    result |= (octet & 0xFF) << shift;
    return result;
}

inline std::string intToIpv4(uint32_t v) {
    char buf[16];
    snprintf(buf, sizeof(buf), "%u.%u.%u.%u",
        (v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
    return buf;
}

inline uint32_t subnetMask(int prefix) {
    return prefix == 0 ? 0u : (0xFFFFFFFFu << (32 - prefix));
}

inline bool isPrivateIpv4(uint32_t ip) {
    uint8_t b0 = (ip >> 24) & 0xFF;
    uint8_t b1 = (ip >> 16) & 0xFF;
    // 10.0.0.0/8
    if (b0 == 10) return true;
    // 172.16.0.0/12
    if (b0 == 172 && (b1 & 0xF0) == 16) return true;
    // 192.168.0.0/16
    if (b0 == 192 && b1 == 168) return true;
    // 100.64.0.0/10 (CGNAT/Tailscale)
    if (b0 == 100 && (b1 & 0xC0) == 64) return true;
    return false;
}

inline bool isCgnatIp(uint32_t ip) {
    uint8_t b0 = (ip >> 24) & 0xFF;
    uint8_t b1 = (ip >> 16) & 0xFF;
    return b0 == 100 && (b1 & 0xC0) == 64;
}

/**
 * Generate all probe IPs for a CIDR subnet.
 * Fills `out` with up to `maxHosts` evenly-sampled host IPs.
 * Returns count added.
 */
int generateCidrTargets(
    uint32_t baseIp,
    int prefix,
    std::vector<std::string>& out,
    int maxHosts = 512
);

} // namespace mdns
