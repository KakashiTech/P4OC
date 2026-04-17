#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include "../include/scroll/scroll_physics.h"

#define TAG "p4oc_scroll"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace p4oc;

// ── Lifecycle ─────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeCreate(
        JNIEnv*, jclass) {
    auto* obj = new ScrollOptimizer();
    return reinterpret_cast<jlong>(obj);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ScrollOptimizer*>(handle);
}

// ── VelocityTracker ───────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeAddSample(
        JNIEnv*, jclass, jlong handle, jlong timeNs, jfloat positionPx) {
    auto* opt = reinterpret_cast<ScrollOptimizer*>(handle);
    opt->tracker.addSample(static_cast<int64_t>(timeNs), positionPx);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeComputeVelocity(
        JNIEnv*, jclass, jlong handle) {
    auto* opt = reinterpret_cast<ScrollOptimizer*>(handle);
    return opt->tracker.computeVelocity();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeResetTracker(
        JNIEnv*, jclass, jlong handle) {
    auto* opt = reinterpret_cast<ScrollOptimizer*>(handle);
    opt->tracker.reset();
    opt->flingActive.store(false);
}

// ── SplineFling ───────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeStartFling(
        JNIEnv*, jclass, jlong handle, jfloat velocityPxS, jfloat friction) {
    auto* opt = reinterpret_cast<ScrollOptimizer*>(handle);
    opt->fling.configure(velocityPxS, friction);
    opt->flingActive.store(true);
}

// Returns [positionOffset, velocity, isRunning(1/0)] as float[3]
extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeFlingFrame(
        JNIEnv* env, jclass, jlong handle, jfloat elapsedS) {
    auto* opt = reinterpret_cast<ScrollOptimizer*>(handle);
    const float t = (opt->fling.durationS > 0.f)
                  ? elapsedS / opt->fling.durationS
                  : 1.f;
    const float pos   = opt->fling.positionAt(t);
    const float vel   = opt->fling.velocityAt(t);
    const float alive = opt->fling.isRunning(elapsedS) ? 1.f : 0.f;
    if (alive == 0.f) opt->flingActive.store(false);

    jfloatArray arr = env->NewFloatArray(3);
    jfloat buf[3] = {pos, vel, alive};
    env->SetFloatArrayRegion(arr, 0, 3, buf);
    return arr;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeFlingDuration(
        JNIEnv*, jclass, jlong handle) {
    auto* opt = reinterpret_cast<ScrollOptimizer*>(handle);
    return opt->fling.durationS;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeScrollOptimizer_nativeFlingDistance(
        JNIEnv*, jclass, jlong handle) {
    auto* opt = reinterpret_cast<ScrollOptimizer*>(handle);
    return opt->fling.totalDistancePx;
}
