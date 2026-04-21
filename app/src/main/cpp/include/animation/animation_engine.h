#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <mutex>
#include <memory>
#include <vector>
#include "easing.h"

namespace animation {

// Animation configuration
struct AnimationConfig {
    int durationMs;
    EasingType easing;
    bool infinite;
    bool reverse; // for reverse mode
    
    AnimationConfig() 
        : durationMs(1000), 
          easing(EasingType::LINEAR), 
          infinite(false), 
          reverse(false) {}
    
    AnimationConfig(int dur, EasingType ease, bool inf = false, bool rev = false)
        : durationMs(dur), easing(ease), infinite(inf), reverse(rev) {}
};

// Cached animation value for a specific time point
struct CachedValue {
    float value;
    uint64_t timestamp;
    
    CachedValue() : value(0.0f), timestamp(0) {}
    CachedValue(float v, uint64_t ts) : value(v), timestamp(ts) {}
};

// Animation instance
class Animation {
public:
    Animation(const std::string& key, const AnimationConfig& config);
    
    // Get value at specific time (0..duration)
    float getValue(int64_t elapsedMs) const;
    
    // Get value with easing applied
    float getValueWithEasing(float progress) const;
    
    // Check if animation is complete
    bool isComplete(int64_t elapsedMs) const;
    
    // Reset animation
    void reset();
    
    // Get config
    const AnimationConfig& getConfig() const { return config_; }
    const std::string& getKey() const { return key_; }
    
private:
    std::string key_;
    AnimationConfig config_;
    EasingFunction easingFunc_;
};

// Thread-safe animation cache
class AnimationCache {
public:
    static AnimationCache& getInstance();
    
    // Get or create animation
    std::shared_ptr<Animation> getOrCreate(
        const std::string& key, 
        const AnimationConfig& config
    );
    
    // Get animation if exists
    std::shared_ptr<Animation> get(const std::string& key);
    
    // Preload common animations
    void preloadCommonAnimations();
    
    // Clear cache
    void clear();
    
    // Get stats
    struct Stats {
        size_t cachedCount;
        size_t memoryEstimateBytes;
    };
    Stats getStats() const;
    
    // Cleanup old entries
    void cleanupUnused();
    
private:
    AnimationCache() = default;
    ~AnimationCache() = default;
    AnimationCache(const AnimationCache&) = delete;
    AnimationCache& operator=(const AnimationCache&) = delete;
    
    mutable std::mutex mutex_;
    std::unordered_map<std::string, std::shared_ptr<Animation>> cache_;
    std::unordered_map<std::string, uint64_t> lastAccessTime_;
};

// Animation engine for calculations
class AnimationEngine {
public:
    static AnimationEngine& getInstance();
    
    // Calculate interpolated value
    static float interpolate(
        float start, 
        float end, 
        float progress, 
        EasingType easing
    );
    
    // Calculate pulse value (0..1..0)
    static float calculatePulse(
        int64_t elapsedMs, 
        int durationMs, 
        EasingType easing
    );
    
    // Calculate rotation value (0..360 continuous)
    static float calculateRotation(
        int64_t elapsedMs, 
        int durationMs
    );
    
    // Calculate with repeat mode
    static float calculateWithRepeatMode(
        int64_t elapsedMs,
        int durationMs,
        EasingType easing,
        bool reverse
    );
    
private:
    AnimationEngine() = default;
    ~AnimationEngine() = default;
};

// Value cache for repeated queries
class ValueCache {
public:
    static ValueCache& getInstance();
    
    // Get cached value or calculate
    float getOrCalculate(
        const std::string& key,
        int64_t elapsedMs,
        std::function<float()> calculator
    );
    
    // Clear cache
    void clear();
    
    // Cleanup old entries
    void cleanup(uint64_t maxAgeMs);
    
private:
    ValueCache() = default;
    ~ValueCache() = default;
    
    mutable std::mutex mutex_;
    std::unordered_map<std::string, CachedValue> cache_;
};

} // namespace animation
