#include "animation/animation_engine.h"
#include <chrono>
#include <algorithm>

namespace animation {

// Animation implementation
Animation::Animation(const std::string& key, const AnimationConfig& config)
    : key_(key), config_(config) {
    easingFunc_ = getEasingFunction(config.easing);
}

float Animation::getValue(int64_t elapsedMs) const {
    if (config_.infinite) {
        // For infinite animations, wrap around
        int64_t wrapped = elapsedMs % config_.durationMs;
        float progress = static_cast<float>(wrapped) / config_.durationMs;
        
        if (config_.reverse) {
            // Ping-pong: go forward then backward
            if (progress < 0.5f) {
                progress = progress * 2.0f;
            } else {
                progress = (1.0f - progress) * 2.0f;
            }
        }
        
        return easingFunc_(progress);
    } else {
        // Finite animation
        float progress = std::min(1.0f, 
            static_cast<float>(elapsedMs) / config_.durationMs);
        return easingFunc_(progress);
    }
}

float Animation::getValueWithEasing(float progress) const {
    // Clamp progress to 0..1
    progress = std::max(0.0f, std::min(1.0f, progress));
    return easingFunc_(progress);
}

bool Animation::isComplete(int64_t elapsedMs) const {
    if (config_.infinite) return false;
    return elapsedMs >= config_.durationMs;
}

void Animation::reset() {
    // Reset any internal state if needed
}

// AnimationCache implementation
AnimationCache& AnimationCache::getInstance() {
    static AnimationCache instance;
    return instance;
}

std::shared_ptr<Animation> AnimationCache::getOrCreate(
    const std::string& key, 
    const AnimationConfig& config
) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto it = cache_.find(key);
    if (it != cache_.end()) {
        // Update access time
        auto now = std::chrono::steady_clock::now().time_since_epoch().count();
        lastAccessTime_[key] = now;
        return it->second;
    }
    
    // Create new animation
    auto animation = std::make_shared<Animation>(key, config);
    cache_[key] = animation;
    
    auto now = std::chrono::steady_clock::now().time_since_epoch().count();
    lastAccessTime_[key] = now;
    
    return animation;
}

std::shared_ptr<Animation> AnimationCache::get(const std::string& key) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto it = cache_.find(key);
    if (it != cache_.end()) {
        auto now = std::chrono::steady_clock::now().time_since_epoch().count();
        lastAccessTime_[key] = now;
        return it->second;
    }
    return nullptr;
}

void AnimationCache::preloadCommonAnimations() {
    // Preload common animation configs
    std::vector<std::pair<std::string, AnimationConfig>> commonConfigs = {
        {"loading_rotation", AnimationConfig(1000, EasingType::LINEAR, true, false)},
        {"pulse_fast", AnimationConfig(800, EasingType::EASE_IN_OUT, true, true)},
        {"pulse_slow", AnimationConfig(1500, EasingType::EASE_IN_OUT, true, true)},
        {"fade_quick", AnimationConfig(150, EasingType::FAST_OUT_SLOW_IN, false, false)},
        {"slide_normal", AnimationConfig(200, EasingType::FAST_OUT_SLOW_IN, false, false)},
        {"shimmer_default", AnimationConfig(6000, EasingType::LINEAR, true, false)}
    };
    
    for (const auto& [key, config] : commonConfigs) {
        getOrCreate(key, config);
    }
}

void AnimationCache::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.clear();
    lastAccessTime_.clear();
}

AnimationCache::Stats AnimationCache::getStats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Stats stats;
    stats.cachedCount = cache_.size();
    // Estimate: each animation ~64 bytes + key string
    stats.memoryEstimateBytes = cache_.size() * 64;
    for (const auto& [key, _] : cache_) {
        stats.memoryEstimateBytes += key.size();
    }
    return stats;
}

void AnimationCache::cleanupUnused() {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto now = std::chrono::steady_clock::now().time_since_epoch().count();
    const uint64_t oneMinuteNs = 60ULL * 1000000000ULL;
    
    std::vector<std::string> toRemove;
    for (const auto& [key, lastAccess] : lastAccessTime_) {
        if (now - lastAccess > oneMinuteNs) {
            // Skip if key contains "active" or "current" or "shimmer"
            if (key.find("active") == std::string::npos &&
                key.find("current") == std::string::npos &&
                key.find("shimmer") == std::string::npos) {
                toRemove.push_back(key);
            }
        }
    }
    
    for (const auto& key : toRemove) {
        cache_.erase(key);
        lastAccessTime_.erase(key);
    }
}

// AnimationEngine implementation
AnimationEngine& AnimationEngine::getInstance() {
    static AnimationEngine instance;
    return instance;
}

float AnimationEngine::interpolate(
    float start, 
    float end, 
    float progress, 
    EasingType easing
) {
    auto easingFunc = getEasingFunction(easing);
    float easedProgress = easingFunc(progress);
    return start + (end - start) * easedProgress;
}

float AnimationEngine::calculatePulse(
    int64_t elapsedMs, 
    int durationMs, 
    EasingType easing
) {
    // Pulse goes from 0.6 to 1.0 and back
    int64_t wrapped = elapsedMs % durationMs;
    float progress = static_cast<float>(wrapped) / durationMs;
    
    // Ping-pong: 0 -> 1 -> 0
    if (progress < 0.5f) {
        progress = progress * 2.0f; // 0 to 1
    } else {
        progress = (1.0f - progress) * 2.0f; // 1 to 0
    }
    
    auto easingFunc = getEasingFunction(easing);
    float eased = easingFunc(progress);
    
    // Map 0..1 to 0.6..1.0
    return 0.6f + eased * 0.4f;
}

float AnimationEngine::calculateRotation(
    int64_t elapsedMs, 
    int durationMs
) {
    // Continuous rotation 0..360
    int64_t wrapped = elapsedMs % durationMs;
    float progress = static_cast<float>(wrapped) / durationMs;
    return progress * 360.0f;
}

float AnimationEngine::calculateWithRepeatMode(
    int64_t elapsedMs,
    int durationMs,
    EasingType easing,
    bool reverse
) {
    int64_t wrapped = elapsedMs % durationMs;
    float progress = static_cast<float>(wrapped) / durationMs;
    
    if (reverse) {
        // Ping-pong mode
        if (progress < 0.5f) {
            progress = progress * 2.0f;
        } else {
            progress = (1.0f - progress) * 2.0f;
        }
    }
    
    auto easingFunc = getEasingFunction(easing);
    return easingFunc(progress);
}

// ValueCache implementation
ValueCache& ValueCache::getInstance() {
    static ValueCache instance;
    return instance;
}

float ValueCache::getOrCalculate(
    const std::string& key,
    int64_t elapsedMs,
    std::function<float()> calculator
) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto it = cache_.find(key);
    if (it != cache_.end()) {
        // Check if still valid (within 16ms frame)
        auto now = std::chrono::steady_clock::now().time_since_epoch().count() / 1000000; // to ms
        if (now - it->second.timestamp < 16) { // Same frame
            return it->second.value;
        }
    }
    
    // Calculate new value
    float value = calculator();
    auto now = std::chrono::steady_clock::now().time_since_epoch().count() / 1000000;
    cache_[key] = CachedValue(value, now);
    return value;
}

void ValueCache::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.clear();
}

void ValueCache::cleanup(uint64_t maxAgeMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    auto now = std::chrono::steady_clock::now().time_since_epoch().count() / 1000000;
    std::vector<std::string> toRemove;
    
    for (const auto& [key, cached] : cache_) {
        if (now - cached.timestamp > maxAgeMs) {
            toRemove.push_back(key);
        }
    }
    
    for (const auto& key : toRemove) {
        cache_.erase(key);
    }
}

} // namespace animation
