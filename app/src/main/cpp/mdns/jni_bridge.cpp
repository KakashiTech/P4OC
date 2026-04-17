#include <jni.h>
#include <string>
#include <cstring>
#include <vector>
#include "mdns/ip_utils.h"
#include "mdns/service_cache.h"

using namespace mdns;

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

static void strncpySafe(char* dst, const char* src, size_t n) {
    std::strncpy(dst, src, n - 1);
    dst[n - 1] = '\0';
}

extern "C" {

// ── IP Utils ─────────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeIpv4ToInt(
    JNIEnv* env, jclass, jstring ip) {
    return static_cast<jint>(ipv4ToInt(jstr(env, ip).c_str()));
}

JNIEXPORT jstring JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeIntToIpv4(
    JNIEnv* env, jclass, jint v) {
    auto s = intToIpv4(static_cast<uint32_t>(v));
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeIsPrivateIpv4(
    JNIEnv* env, jclass, jstring ip) {
    return isPrivateIpv4(ipv4ToInt(jstr(env, ip).c_str())) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeIsCgnatIp(
    JNIEnv* env, jclass, jstring ip) {
    return isCgnatIp(ipv4ToInt(jstr(env, ip).c_str())) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Generate CIDR targets for subnet sweep.
 * Returns String[] of IPs (up to maxHosts).
 */
JNIEXPORT jobjectArray JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeGenerateCidrTargets(
    JNIEnv* env, jclass,
    jstring baseIp, jint prefix, jint maxHosts) {

    jclass strClass = env->FindClass("java/lang/String");
    uint32_t ip = ipv4ToInt(jstr(env, baseIp).c_str());

    std::vector<std::string> out;
    out.reserve(static_cast<size_t>(maxHosts));
    int n = generateCidrTargets(ip, prefix, out, maxHosts);

    jobjectArray arr = env->NewObjectArray(n, strClass, nullptr);
    for (int i = 0; i < n; i++) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(out[i].c_str()));
    }
    return arr;
}

// ── ServiceCache ──────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeCacheCreate(
    JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new ServiceCache());
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeCacheDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ServiceCache*>(handle);
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeCacheUpsert(
    JNIEnv* env, jclass, jlong handle,
    jstring name, jstring host, jint port, jstring url) {
    auto* cache = reinterpret_cast<ServiceCache*>(handle);
    if (!cache) return;

    NativeService svc;
    strncpySafe(svc.name, jstr(env, name).c_str(), sizeof(svc.name));
    strncpySafe(svc.host, jstr(env, host).c_str(), sizeof(svc.host));
    svc.port = port;
    strncpySafe(svc.url, jstr(env, url).c_str(), sizeof(svc.url));
    cache->upsert(svc);
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeCacheRemove(
    JNIEnv* env, jclass, jlong handle, jstring name) {
    auto* cache = reinterpret_cast<ServiceCache*>(handle);
    if (!cache) return JNI_FALSE;
    return cache->remove(jstr(env, name).c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeCacheGetAll(
    JNIEnv* env, jclass, jlong handle) {
    jclass strClass = env->FindClass("java/lang/String");
    auto* cache = reinterpret_cast<ServiceCache*>(handle);
    if (!cache) return env->NewObjectArray(0, strClass, nullptr);

    auto services = cache->getAll();
    // Return as "name|host|port|url" strings
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(services.size()), strClass, nullptr);
    for (size_t i = 0; i < services.size(); i++) {
        char buf[512];
        snprintf(buf, sizeof(buf), "%s|%s|%d|%s",
            services[i].name, services[i].host,
            services[i].port, services[i].url);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i),
            env->NewStringUTF(buf));
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeCacheClear(
    JNIEnv*, jclass, jlong handle) {
    auto* cache = reinterpret_cast<ServiceCache*>(handle);
    if (cache) cache->clear();
}

JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_network_NativeMdnsSupport_nativeCacheSize(
    JNIEnv*, jclass, jlong handle) {
    auto* cache = reinterpret_cast<ServiceCache*>(handle);
    return cache ? cache->size() : 0;
}

} // extern "C"
