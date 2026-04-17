#include <jni.h>
#include <string>
#include "sse/event_buffer.h"

using namespace sse;

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

// ── EventBuffer ───────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBufferCreate(
    JNIEnv*, jclass, jint capacity) {
    return reinterpret_cast<jlong>(new EventBuffer(capacity));
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBufferDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<EventBuffer*>(handle);
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBufferPush(
    JNIEnv* env, jclass, jlong handle, jstring data) {
    auto* buf = reinterpret_cast<EventBuffer*>(handle);
    if (!buf || !data) return JNI_FALSE;
    const char* c = env->GetStringUTFChars(data, nullptr);
    bool ok = buf->push(c, static_cast<size_t>(env->GetStringUTFLength(data)));
    env->ReleaseStringUTFChars(data, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBufferDrain(
    JNIEnv* env, jclass, jlong handle, jint max) {
    auto* buf = reinterpret_cast<EventBuffer*>(handle);
    jclass strClass = env->FindClass("java/lang/String");
    if (!buf) return env->NewObjectArray(0, strClass, nullptr);

    std::vector<std::string> out;
    out.reserve(static_cast<size_t>(max));
    int n = buf->drain(out, max);

    jobjectArray arr = env->NewObjectArray(n, strClass, nullptr);
    for (int i = 0; i < n; i++) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(out[i].c_str()));
    }
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBufferStats(
    JNIEnv* env, jclass, jlong handle) {
    jlongArray arr = env->NewLongArray(3);
    auto* buf = reinterpret_cast<EventBuffer*>(handle);
    if (!buf) return arr;
    jlong vals[3] = {
        static_cast<jlong>(buf->size()),
        buf->totalPushed(),
        buf->totalDropped()
    };
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBufferClear(
    JNIEnv*, jclass, jlong handle) {
    auto* buf = reinterpret_cast<EventBuffer*>(handle);
    if (buf) buf->clear();
}

// ── SseBackoff ────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBackoffCreate(
    JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new SseBackoff());
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBackoffDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<SseBackoff*>(handle);
}

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_network_NativeSseSupport_nativeBackoffComputeDelay(
    JNIEnv*, jclass, jlong handle, jint consecutiveErrors) {
    auto* bo = reinterpret_cast<SseBackoff*>(handle);
    if (!bo) return 2000;
    return bo->computeDelayMs(consecutiveErrors);
}

} // extern "C"
