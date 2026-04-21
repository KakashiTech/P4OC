#include <jni.h>
#include <string>
#include "pty/output_buffer.h"
#include "pty/reconnection_manager.h"

using namespace pty;

extern "C" {

// ── OutputBuffer ─────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeBufferCreate(
    JNIEnv*, jclass, jint capacity) {
    return reinterpret_cast<jlong>(new OutputBuffer(capacity));
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeBufferDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<OutputBuffer*>(handle);
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeBufferPush(
    JNIEnv* env, jclass, jlong handle, jstring text) {
    auto* buf = reinterpret_cast<OutputBuffer*>(handle);
    if (!buf || !text) return JNI_FALSE;
    const char* c = env->GetStringUTFChars(text, nullptr);
    bool ok = buf->push(c, static_cast<size_t>(env->GetStringUTFLength(text)));
    env->ReleaseStringUTFChars(text, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeBufferDrain(
    JNIEnv* env, jclass, jlong handle, jint max) {
    auto* buf = reinterpret_cast<OutputBuffer*>(handle);
    jclass stringClass = env->FindClass("java/lang/String");
    if (!buf) return env->NewObjectArray(0, stringClass, nullptr);

    std::vector<std::string> out;
    out.reserve(static_cast<size_t>(max));
    int n = buf->drain(out, max);

    jobjectArray arr = env->NewObjectArray(n, stringClass, nullptr);
    for (int i = 0; i < n; i++) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(out[i].c_str()));
    }
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeBufferStats(
    JNIEnv* env, jclass, jlong handle) {
    jlongArray arr = env->NewLongArray(3);
    auto* buf = reinterpret_cast<OutputBuffer*>(handle);
    if (!buf) return arr;
    jlong vals[3] = {
        static_cast<jlong>(buf->size()),
        buf->totalReceived(),
        buf->totalDropped()
    };
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeBufferClear(
    JNIEnv*, jclass, jlong handle) {
    auto* buf = reinterpret_cast<OutputBuffer*>(handle);
    if (buf) buf->clear();
}

// ── ReconnectionManager ───────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeReconnectCreate(
    JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new ReconnectionManager());
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeReconnectDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ReconnectionManager*>(handle);
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeReconnectOnConnected(
    JNIEnv*, jclass, jlong handle) {
    auto* rm = reinterpret_cast<ReconnectionManager*>(handle);
    if (rm) rm->onConnected();
}

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeReconnectNextDelay(
    JNIEnv*, jclass, jlong handle) {
    auto* rm = reinterpret_cast<ReconnectionManager*>(handle);
    if (!rm) return -1;
    return rm->nextDelayMs();
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeReconnectShouldRetry(
    JNIEnv*, jclass, jlong handle) {
    auto* rm = reinterpret_cast<ReconnectionManager*>(handle);
    if (!rm) return JNI_FALSE;
    return rm->shouldReconnect() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeReconnectReset(
    JNIEnv*, jclass, jlong handle) {
    auto* rm = reinterpret_cast<ReconnectionManager*>(handle);
    if (rm) rm->reset();
}

JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_network_NativePtySupport_nativeReconnectAttempts(
    JNIEnv*, jclass, jlong handle) {
    auto* rm = reinterpret_cast<ReconnectionManager*>(handle);
    if (!rm) return 0;
    return rm->attempts();
}

} // extern "C"
