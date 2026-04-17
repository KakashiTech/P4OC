#include <jni.h>
#include <string>
#include <cstring>
#include "buffer/message_buffer.h"

using namespace buffer;

static std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* c = env->GetStringUTFChars(jstr, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(jstr, c);
    return s;
}

static void strncpySafe(char* dst, const char* src, size_t n) {
    std::strncpy(dst, src, n - 1);
    dst[n - 1] = '\0';
}

extern "C" {

// --- Lifecycle ---

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeCreate(
    JNIEnv*, jclass, jint maxSize) {
    return reinterpret_cast<jlong>(new NativeMessageBuffer(maxSize));
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<NativeMessageBuffer*>(handle);
}

// --- Write ---

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeAdd(
    JNIEnv* env, jclass,
    jlong handle,
    jstring id,
    jstring sessionId,
    jlong createdAt,
    jlong completedAt,
    jint messageType,
    jboolean hasTools,
    jboolean hasSummary,
    jint tokenInput,
    jint tokenOutput
) {
    auto* buf = reinterpret_cast<NativeMessageBuffer*>(handle);
    if (!buf) return JNI_FALSE;

    NativeMessage msg;
    strncpySafe(msg.id, jstringToStd(env, id).c_str(), sizeof(msg.id));
    strncpySafe(msg.sessionId, jstringToStd(env, sessionId).c_str(), sizeof(msg.sessionId));
    msg.createdAt   = createdAt;
    msg.completedAt = completedAt;
    msg.messageType = messageType;
    msg.hasTools    = hasTools;
    msg.hasSummary  = hasSummary;
    msg.tokenInput  = tokenInput;
    msg.tokenOutput = tokenOutput;

    return buf->add(msg) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeRemove(
    JNIEnv* env, jclass, jlong handle, jstring messageId) {
    auto* buf = reinterpret_cast<NativeMessageBuffer*>(handle);
    if (!buf) return JNI_FALSE;
    return buf->remove(jstringToStd(env, messageId).c_str()) ? JNI_TRUE : JNI_FALSE;
}

// --- Read ---

JNIEXPORT jobjectArray JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeGetLast(
    JNIEnv* env, jclass, jlong handle, jint limit) {
    auto* buf = reinterpret_cast<NativeMessageBuffer*>(handle);

    jclass stringClass = env->FindClass("java/lang/String");
    if (!buf) return env->NewObjectArray(0, stringClass, nullptr);

    auto msgs = buf->getLast(limit);

    // Return as String[] of "<id>|<sessionId>|<createdAt>|<type>" for simplicity
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(msgs.size()), stringClass, nullptr);
    for (size_t i = 0; i < msgs.size(); i++) {
        char tmp[256];
        snprintf(tmp, sizeof(tmp), "%s|%s|%lld|%d|%d|%d",
            msgs[i].id, msgs[i].sessionId,
            static_cast<long long>(msgs[i].createdAt),
            msgs[i].messageType,
            msgs[i].tokenInput,
            msgs[i].tokenOutput);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), env->NewStringUTF(tmp));
    }
    return arr;
}

// --- Eviction ---

JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeEvictOldest(
    JNIEnv*, jclass, jlong handle, jint count) {
    auto* buf = reinterpret_cast<NativeMessageBuffer*>(handle);
    if (!buf) return 0;
    return buf->evictOldest(count);
}

JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeEvictByAge(
    JNIEnv*, jclass, jlong handle, jlong cutoffMs) {
    auto* buf = reinterpret_cast<NativeMessageBuffer*>(handle);
    if (!buf) return 0;
    return buf->evictByAge(cutoffMs);
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeClear(
    JNIEnv*, jclass, jlong handle) {
    auto* buf = reinterpret_cast<NativeMessageBuffer*>(handle);
    if (buf) buf->clear();
}

// --- Stats ---

JNIEXPORT jlongArray JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMessageBuffer_nativeGetStats(
    JNIEnv* env, jclass, jlong handle) {
    jlongArray arr = env->NewLongArray(4);
    auto* buf = reinterpret_cast<NativeMessageBuffer*>(handle);
    if (!buf) return arr;

    const auto& s = buf->stats();
    jlong vals[4] = {
        s.currentSize.load(),
        s.maxSize,
        s.totalProcessed.load(),
        s.evictionCount.load()
    };
    env->SetLongArrayRegion(arr, 0, 4, vals);
    return arr;
}

} // extern "C"
