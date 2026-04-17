#include "mdns/ip_utils.h"
#include <algorithm>

namespace mdns {

int generateCidrTargets(
    uint32_t baseIp,
    int prefix,
    std::vector<std::string>& out,
    int maxHosts)
{
    if (prefix < 0 || prefix > 32) return 0;

    uint32_t mask  = subnetMask(prefix);
    uint32_t net   = baseIp & mask;
    uint32_t low   = net + 1;
    uint32_t high  = (net | ~mask) - 1;

    if (low > high) return 0;

    uint32_t span = high - low + 1;
    uint32_t step = (span / static_cast<uint32_t>(maxHosts));
    if (step < 1) step = 1;

    int added = 0;
    uint32_t ip = low;
    while (ip <= high && added < maxHosts) {
        out.push_back(intToIpv4(ip));
        ip += step;
        ++added;
    }
    return added;
}

} // namespace mdns
