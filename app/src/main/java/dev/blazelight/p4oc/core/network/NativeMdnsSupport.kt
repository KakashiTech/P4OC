package dev.blazelight.p4oc.core.network

import java.net.Inet4Address
import java.net.InetAddress

object NativeMdnsSupport {
    @Volatile private var loadAttempted = false
    @Volatile private var nativeAvailable = false

    @Synchronized fun ensureLoaded(): Boolean {
        if (loadAttempted) return nativeAvailable
        loadAttempted = true
        return try {
            System.loadLibrary("p4oc_mdns")
            nativeAvailable = true
            true
        } catch (_: Throwable) {
            nativeAvailable = false
            false
        }
    }
    fun warmup() { ensureLoaded() }

    fun ipv4ToInt(ip: String): Int = if (nativeAvailable) nativeIpv4ToInt(ip) else kotlinIpv4ToInt(ip)
    fun intToIpv4(v: Int): String = if (nativeAvailable) nativeIntToIpv4(v) else kotlinIntToIpv4(v)
    fun isPrivateIpv4(ip: String): Boolean = if (nativeAvailable) nativeIsPrivateIpv4(ip) else kotlinIsPrivateIpv4(ip)
    fun isCgnatIp(ip: String): Boolean = if (nativeAvailable) nativeIsCgnatIp(ip) else kotlinIsCgnatIp(ip)
    fun generateCidrTargets(baseIp: String, prefix: Int, maxHosts: Int = 512): Array<String> =
        if (nativeAvailable) nativeGenerateCidrTargets(baseIp, prefix, maxHosts)
        else kotlinGenerateCidrTargets(baseIp, prefix, maxHosts)

    fun createCache(): Long = if (nativeAvailable) nativeCacheCreate() else kotlinCacheCreate()
    fun destroyCache(handle: Long) { if (nativeAvailable) nativeCacheDestroy(handle) else kotlinCacheDestroy(handle) }
    fun upsert(handle: Long, name: String, host: String, port: Int, url: String) {
        if (nativeAvailable) nativeCacheUpsert(handle, name, host, port, url)
        else kotlinCacheUpsert(handle, name, host, port, url)
    }
    fun remove(handle: Long, name: String): Boolean =
        if (nativeAvailable) nativeCacheRemove(handle, name) else kotlinCacheRemove(handle, name)
    fun getAll(handle: Long): Array<String> =
        if (nativeAvailable) nativeCacheGetAll(handle) else kotlinCacheGetAll(handle)
    fun clearCache(handle: Long) { if (nativeAvailable) nativeCacheClear(handle) else kotlinCacheClear(handle) }
    fun cacheSize(handle: Long): Int =
        if (nativeAvailable) nativeCacheSize(handle) else kotlinCacheSize(handle)

    fun parseService(packed: String): DiscoveredServer? {
        val p = packed.split("|")
        if (p.size < 4) return null
        return DiscoveredServer(
            serviceName = p[0],
            host = p[1],
            port = p[2].toIntOrNull() ?: return null,
            url = p[3]
        )
    }

    // ── Kotlin fallbacks ───────────────────────────────────────────────────────

    private val kotlinCaches = mutableMapOf<Long, MutableMap<String, String>>()
    private var nextCacheId = 1L
    private val cacheLock = Any()

    private fun kotlinIpv4ToInt(ip: String): Int {
        val parts = ip.split(".")
        if (parts.size != 4) return 0
        return (parts[0].toIntOrNull() ?: 0) shl 24 or
               ((parts[1].toIntOrNull() ?: 0) shl 16) or
               ((parts[2].toIntOrNull() ?: 0) shl 8) or
               (parts[3].toIntOrNull() ?: 0)
    }

    private fun kotlinIntToIpv4(v: Int): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    private fun kotlinIsPrivateIpv4(ip: String): Boolean {
        val v = kotlinIpv4ToInt(ip)
        return (v and 0xFF000000.toInt()) == 0x0A000000 ||
               (v and 0xFFF00000.toInt()) == 0xAC100000.toInt() ||
               (v and 0xFFFF0000.toInt()) == 0xC0A80000.toInt() ||
               v == 0x7F000001
    }

    private fun kotlinIsCgnatIp(ip: String): Boolean {
        val v = kotlinIpv4ToInt(ip)
        return (v and 0xFFF00000.toInt()) == 0x64400000
    }

    private fun kotlinGenerateCidrTargets(baseIp: String, prefix: Int, maxHosts: Int): Array<String> {
        val base = kotlinIpv4ToInt(baseIp) and (0xFFFFFFFF.toInt() shl (32 - prefix))
        val total = (1 shl (32 - prefix)) - 2
        val step = if (total <= maxHosts) 1 else total / maxHosts
        val result = mutableListOf<String>()
        var i = 1
        while (i <= total && result.size < maxHosts) {
            result.add(kotlinIntToIpv4(base or i))
            i += step
        }
        return result.toTypedArray()
    }

    private fun kotlinCacheCreate(): Long = synchronized(cacheLock) {
        val id = nextCacheId++
        kotlinCaches[id] = mutableMapOf()
        id
    }

    private fun kotlinCacheDestroy(handle: Long) = synchronized(cacheLock) {
        kotlinCaches.remove(handle)
    }

    private fun kotlinCacheUpsert(handle: Long, name: String, host: String, port: Int, url: String) = synchronized(cacheLock) {
        kotlinCaches[handle]?.put(name, "$name|$host|$port|$url")
    }

    private fun kotlinCacheRemove(handle: Long, name: String): Boolean = synchronized(cacheLock) {
        kotlinCaches[handle]?.remove(name) != null
    }

    private fun kotlinCacheGetAll(handle: Long): Array<String> = synchronized(cacheLock) {
        kotlinCaches[handle]?.values?.toTypedArray() ?: emptyArray()
    }

    private fun kotlinCacheClear(handle: Long) = synchronized(cacheLock) {
        kotlinCaches[handle]?.clear()
    }

    private fun kotlinCacheSize(handle: Long): Int = synchronized(cacheLock) {
        kotlinCaches[handle]?.size ?: 0
    }

    // ── JNI (kept for when .so is available) ───────────────────────────────────

    @JvmStatic private external fun nativeIpv4ToInt(ip: String): Int
    @JvmStatic private external fun nativeIntToIpv4(v: Int): String
    @JvmStatic private external fun nativeIsPrivateIpv4(ip: String): Boolean
    @JvmStatic private external fun nativeIsCgnatIp(ip: String): Boolean
    @JvmStatic private external fun nativeGenerateCidrTargets(
        baseIp: String, prefix: Int, maxHosts: Int): Array<String>

    @JvmStatic private external fun nativeCacheCreate(): Long
    @JvmStatic private external fun nativeCacheDestroy(handle: Long)
    @JvmStatic private external fun nativeCacheUpsert(
        handle: Long, name: String, host: String, port: Int, url: String)
    @JvmStatic private external fun nativeCacheRemove(handle: Long, name: String): Boolean
    @JvmStatic private external fun nativeCacheGetAll(handle: Long): Array<String>
    @JvmStatic private external fun nativeCacheClear(handle: Long)
    @JvmStatic private external fun nativeCacheSize(handle: Long): Int
}
