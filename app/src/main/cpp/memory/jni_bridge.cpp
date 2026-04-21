#include <jni.h>
#include "memory/pressure_classifier.h"

using namespace memory;

extern "C" {

// ── PressureClassifier ────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeClassifierCreate(
    JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new PressureClassifier());
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeClassifierDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<PressureClassifier*>(handle);
}

/**
 * Returns pressure level ordinal: 0=LOW, 1=MEDIUM, 2=HIGH, 3=CRITICAL.
 * Pure arithmetic, no allocation.
 */
JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeClassify(
    JNIEnv*, jclass, jlong totalBytes, jlong availableBytes) {
    return static_cast<jint>(PressureClassifier::classify(totalBytes, availableBytes));
}

JNIEXPORT jint JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeClassifyHysteresis(
    JNIEnv*, jclass, jlong handle, jlong totalBytes, jlong availableBytes, jint minCycles) {
    auto* c = reinterpret_cast<PressureClassifier*>(handle);
    if (!c) return 0;
    return static_cast<jint>(c->classifyWithHysteresis(totalBytes, availableBytes, minCycles));
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeClassifierReset(
    JNIEnv*, jclass, jlong handle) {
    auto* c = reinterpret_cast<PressureClassifier*>(handle);
    if (c) c->reset();
}

// ── SlabPool ──────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeSlabCreate(
    JNIEnv*, jclass, jint slabSize, jint poolSize) {
    return reinterpret_cast<jlong>(new SlabPool(slabSize, poolSize));
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeSlabDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<SlabPool*>(handle);
}

/**
 * Acquire a slab. Returns direct ByteBuffer backed by native memory,
 * or null if pool exhausted.
 */
JNIEXPORT jobject JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeSlabAcquire(
    JNIEnv* env, jclass, jlong handle) {
    auto* pool = reinterpret_cast<SlabPool*>(handle);
    if (!pool) return nullptr;
    char* slab = pool->acquire();
    if (!slab) return nullptr;
    return env->NewDirectByteBuffer(slab, pool->slabSize());
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeSlabRelease(
    JNIEnv* env, jclass, jlong handle, jobject byteBuffer) {
    auto* pool = reinterpret_cast<SlabPool*>(handle);
    if (!pool || !byteBuffer) return;
    char* slab = static_cast<char*>(env->GetDirectBufferAddress(byteBuffer));
    pool->release(slab);
}

/**
 * Returns long[4]: available, poolSize, totalAcquired, totalReleased
 */
JNIEXPORT jlongArray JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeMemorySupport_nativeSlabStats(
    JNIEnv* env, jclass, jlong handle) {
    jlongArray arr = env->NewLongArray(4);
    auto* pool = reinterpret_cast<SlabPool*>(handle);
    if (!pool) return arr;
    jlong vals[4] = {
        static_cast<jlong>(pool->available()),
        static_cast<jlong>(pool->poolSize()),
        pool->totalAcquired(),
        pool->totalReleased()
    };
    env->SetLongArrayRegion(arr, 0, 4, vals);
    return arr;
}

} // extern "C"
