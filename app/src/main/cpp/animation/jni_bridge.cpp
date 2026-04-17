#include <jni.h>
#include <string>
#include <memory>
#include "animation/animation_engine.h"

using namespace animation;

// Helper to convert Java string to std::string
static std::string jstringToStdString(JNIEnv* env, jstring jStr) {
    if (!jStr) return "";
    const char* cStr = env->GetStringUTFChars(jStr, nullptr);
    std::string str(cStr);
    env->ReleaseStringUTFChars(jStr, cStr);
    return str;
}

// Helper to convert EasingType
static EasingType intToEasingType(jint type) {
    switch (type) {
        case 0: return EasingType::LINEAR;
        case 1: return EasingType::EASE_IN_OUT;
        case 2: return EasingType::FAST_OUT_SLOW_IN;
        case 3: return EasingType::EASE_OUT;
        case 4: return EasingType::EASE_IN;
        case 5: return EasingType::ELASTIC;
        default: return EasingType::LINEAR;
    }
}

extern "C" {

// Initialize the animation cache
JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeInitialize(JNIEnv* env, jclass clazz) {
    AnimationCache::getInstance().preloadCommonAnimations();
}

// Create or get animation
JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeGetOrCreateAnimation(
    JNIEnv* env, 
    jclass clazz,
    jstring key,
    jint durationMs,
    jint easingType,
    jboolean infinite,
    jboolean reverse
) {
    std::string keyStr = jstringToStdString(env, key);
    
    AnimationConfig config;
    config.durationMs = durationMs;
    config.easing = intToEasingType(easingType);
    config.infinite = infinite;
    config.reverse = reverse;
    
    auto animation = AnimationCache::getInstance().getOrCreate(keyStr, config);
    
    // Return pointer as jlong (for caching the reference)
    return reinterpret_cast<jlong>(animation.get());
}

// Get animation value at time
JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeGetValue(
    JNIEnv* env,
    jclass clazz,
    jlong animationPtr,
    jlong elapsedMs
) {
    if (animationPtr == 0) return 0.0f;
    
    auto* animation = reinterpret_cast<Animation*>(animationPtr);
    return animation->getValue(static_cast<int64_t>(elapsedMs));
}

// Calculate interpolated value
JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeInterpolate(
    JNIEnv* env,
    jclass clazz,
    jfloat start,
    jfloat end,
    jfloat progress,
    jint easingType
) {
    return AnimationEngine::interpolate(
        start, 
        end, 
        progress, 
        intToEasingType(easingType)
    );
}

// Calculate pulse value
JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeCalculatePulse(
    JNIEnv* env,
    jclass clazz,
    jlong elapsedMs,
    jint durationMs,
    jint easingType
) {
    return AnimationEngine::calculatePulse(
        static_cast<int64_t>(elapsedMs),
        durationMs,
        intToEasingType(easingType)
    );
}

// Calculate rotation value
JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeCalculateRotation(
    JNIEnv* env,
    jclass clazz,
    jlong elapsedMs,
    jint durationMs
) {
    return AnimationEngine::calculateRotation(
        static_cast<int64_t>(elapsedMs),
        durationMs
    );
}

// Calculate with repeat mode
JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeCalculateWithRepeat(
    JNIEnv* env,
    jclass clazz,
    jlong elapsedMs,
    jint durationMs,
    jint easingType,
    jboolean reverse
) {
    return AnimationEngine::calculateWithRepeatMode(
        static_cast<int64_t>(elapsedMs),
        durationMs,
        intToEasingType(easingType),
        reverse
    );
}

// Preload common animations
JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativePreloadCommon(JNIEnv* env, jclass clazz) {
    AnimationCache::getInstance().preloadCommonAnimations();
}

// Clear cache
JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeClearCache(JNIEnv* env, jclass clazz) {
    AnimationCache::getInstance().clear();
    ValueCache::getInstance().clear();
}

// Cleanup unused
JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeCleanupUnused(JNIEnv* env, jclass clazz) {
    AnimationCache::getInstance().cleanupUnused();
}

// Get stats
JNIEXPORT jobject JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeGetStats(
    JNIEnv* env, 
    jclass clazz
) {
    auto stats = AnimationCache::getInstance().getStats();
    
    // Create HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
    
    jmethodID put = env->GetMethodID(hashMapClass, "put", 
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    
    // Put values
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    
    jobject cachedCount = env->CallStaticObjectMethod(longClass, longValueOf, 
        static_cast<jlong>(stats.cachedCount));
    jobject memoryEstimate = env->CallStaticObjectMethod(longClass, longValueOf,
        static_cast<jlong>(stats.memoryEstimateBytes));
    
    jstring key1 = env->NewStringUTF("cached_animations");
    jstring key2 = env->NewStringUTF("memory_usage_estimate");
    
    env->CallObjectMethod(hashMap, put, key1, cachedCount);
    env->CallObjectMethod(hashMap, put, key2, memoryEstimate);
    
    // Cleanup local refs
    env->DeleteLocalRef(key1);
    env->DeleteLocalRef(key2);
    env->DeleteLocalRef(cachedCount);
    env->DeleteLocalRef(memoryEstimate);
    env->DeleteLocalRef(longClass);
    env->DeleteLocalRef(hashMapClass);
    
    return hashMap;
}

// Calculate easing directly (for ChatInputBar shimmer)
JNIEXPORT jfloat JNICALL
Java_dev_blazelight_p4oc_core_performance_NativeAnimationOptimizer_nativeCalculateEasing(
    JNIEnv* env,
    jclass clazz,
    jfloat t,
    jint easingType
) {
    auto easingFunc = getEasingFunction(intToEasingType(easingType));
    return easingFunc(t);
}

} // extern "C"
