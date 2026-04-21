#include <jni.h>
#include <string>
#include <chrono>
#include <vector>
#include "pool/connection_registry.h"

using namespace pool;

static int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeCreate(
    JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new ConnectionRegistry());
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ConnectionRegistry*>(handle);
}

JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeAcquire(
    JNIEnv* env, jclass, jlong handle, jstring url) {
    auto* reg = reinterpret_cast<ConnectionRegistry*>(handle);
    if (!reg) return -1;
    return reg->acquire(jstr(env, url).c_str(), nowMs());
}

JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeRegister(
    JNIEnv* env, jclass, jlong handle, jstring url, jint clientId) {
    auto* reg = reinterpret_cast<ConnectionRegistry*>(handle);
    if (!reg) return -1;
    return reg->registerSlot(jstr(env, url).c_str(), clientId, nowMs());
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeRelease(
    JNIEnv*, jclass, jlong handle, jint slotId) {
    auto* reg = reinterpret_cast<ConnectionRegistry*>(handle);
    if (!reg) return JNI_FALSE;
    return reg->release(slotId, nowMs()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeEvictStale(
    JNIEnv* env, jclass, jlong handle) {
    auto* reg = reinterpret_cast<ConnectionRegistry*>(handle);
    std::vector<int> evicted;
    if (reg) reg->evictStale(nowMs(), evicted);
    jintArray arr = env->NewIntArray(static_cast<jsize>(evicted.size()));
    if (!evicted.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(evicted.size()),
            reinterpret_cast<const jint*>(evicted.data()));
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeIsHealthy(
    JNIEnv*, jclass, jlong handle, jint slotId) {
    auto* reg = reinterpret_cast<ConnectionRegistry*>(handle);
    if (!reg) return JNI_FALSE;
    return reg->isHealthy(slotId, nowMs()) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Returns float[5]: activeCount, pooledCount, totalCreated, totalEvicted, reuseRate
 */
JNIEXPORT jfloatArray JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeGetStats(
    JNIEnv* env, jclass, jlong handle) {
    jfloatArray arr = env->NewFloatArray(5);
    auto* reg = reinterpret_cast<ConnectionRegistry*>(handle);
    if (!reg) return arr;
    PoolStats s = reg->getStats();
    jfloat vals[5] = {
        static_cast<jfloat>(s.activeCount),
        static_cast<jfloat>(s.pooledCount),
        static_cast<jfloat>(s.totalCreated),
        static_cast<jfloat>(s.totalEvicted),
        s.reuseRate
    };
    env->SetFloatArrayRegion(arr, 0, 5, vals);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeConnectionPool_nativeClear(
    JNIEnv* env, jclass, jlong handle) {
    auto* reg = reinterpret_cast<ConnectionRegistry*>(handle);
    std::vector<int> allIds;
    if (reg) reg->clear(allIds);
    jintArray arr = env->NewIntArray(static_cast<jsize>(allIds.size()));
    if (!allIds.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(allIds.size()),
            reinterpret_cast<const jint*>(allIds.data()));
    }
    return arr;
}

} // extern "C"
