#pragma once

#include <cmath>

namespace animation {

// Easing function type
typedef float (*EasingFunction)(float t);

// Linear easing
inline float linear(float t) {
    return t;
}

// Ease In Out Quad (smooth start/end)
inline float easeInOutQuad(float t) {
    return t < 0.5f ? 2.0f * t * t : 1.0f - std::pow(-2.0f * t + 2.0f, 2.0f) / 2.0f;
}

// Ease In Out Cubic (stronger acceleration)
inline float easeInOutCubic(float t) {
    return t < 0.5f ? 4.0f * t * t * t : 1.0f - std::pow(-2.0f * t + 2.0f, 3.0f) / 2.0f;
}

// Fast Out Slow In (material design)
inline float fastOutSlowIn(float t) {
    // Approximation of cubic-bezier(0.4, 0.0, 0.2, 1.0)
    return easeInOutCubic(t);
}

// Ease Out Quad (decelerate)
inline float easeOutQuad(float t) {
    return 1.0f - (1.0f - t) * (1.0f - t);
}

// Ease In Quad (accelerate)
inline float easeInQuad(float t) {
    return t * t;
}

// Elastic easing (for bounce effects)
inline float easeOutElastic(float t) {
    const float c4 = (2.0f * M_PI) / 3.0f;
    if (t == 0.0f) return 0.0f;
    if (t == 1.0f) return 1.0f;
    return std::pow(2.0f, -10.0f * t) * std::sin((t * 10.0f - 0.75f) * c4) + 1.0f;
}

// Get easing function by type
enum class EasingType {
    LINEAR = 0,
    EASE_IN_OUT = 1,
    FAST_OUT_SLOW_IN = 2,
    EASE_OUT = 3,
    EASE_IN = 4,
    ELASTIC = 5
};

inline EasingFunction getEasingFunction(EasingType type) {
    switch (type) {
        case EasingType::LINEAR: return linear;
        case EasingType::EASE_IN_OUT: return easeInOutQuad;
        case EasingType::FAST_OUT_SLOW_IN: return fastOutSlowIn;
        case EasingType::EASE_OUT: return easeOutQuad;
        case EasingType::EASE_IN: return easeInQuad;
        case EasingType::ELASTIC: return easeOutElastic;
        default: return linear;
    }
}

} // namespace animation
