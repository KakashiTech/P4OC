cmake_minimum_required(VERSION 3.18.1)
project("p4oc_native")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Animation library
add_library(
    p4oc_animation
    SHARED
    animation/animation_engine.cpp
    animation/jni_bridge.cpp
)

# Buffer library
add_library(
    p4oc_buffer
    SHARED
    buffer/message_buffer.cpp
    buffer/jni_bridge.cpp
)

# PTY support library
add_library(
    p4oc_pty
    SHARED
    pty/output_buffer.cpp
    pty/reconnection_manager.cpp
    pty/jni_bridge.cpp
)

# mDNS support library
add_library(
    p4oc_mdns
    SHARED
    mdns/ip_utils.cpp
    mdns/service_cache.cpp
    mdns/jni_bridge.cpp
)

# SSE support library
add_library(
    p4oc_sse
    SHARED
    sse/event_buffer.cpp
    sse/jni_bridge.cpp
)

# Connection pool registry library
add_library(
    p4oc_pool
    SHARED
    pool/connection_registry.cpp
    pool/jni_bridge.cpp
)

# Memory support library
add_library(
    p4oc_memory
    SHARED
    memory/pressure_classifier.cpp
    memory/jni_bridge.cpp
)

# Scroll physics library — native spline fling + velocity tracker
add_library(
    p4oc_scroll
    SHARED
    scroll/jni_bridge.cpp
)

# Find Android log library
find_library(
    log-lib
    log
)

# Link libraries
target_link_libraries(
    p4oc_animation
    ${log-lib}
)

target_link_libraries(
    p4oc_buffer
    ${log-lib}
)

target_link_libraries(
    p4oc_pty
    ${log-lib}
)

target_link_libraries(
    p4oc_mdns
    ${log-lib}
)

target_link_libraries(
    p4oc_sse
    ${log-lib}
)

target_link_libraries(
    p4oc_pool
    ${log-lib}
)

target_link_libraries(
    p4oc_memory
    ${log-lib}
)

target_link_libraries(
    p4oc_scroll
    ${log-lib}
)

# Compiler flags for performance
target_compile_options(p4oc_animation PRIVATE -O3 -ffast-math -funroll-loops -fomit-frame-pointer)
target_compile_options(p4oc_buffer    PRIVATE -O3 -funroll-loops -fomit-frame-pointer)
target_compile_options(p4oc_pty       PRIVATE -O3 -funroll-loops -fomit-frame-pointer)
target_compile_options(p4oc_mdns      PRIVATE -O3 -funroll-loops -fomit-frame-pointer)
target_compile_options(p4oc_sse       PRIVATE -O3 -funroll-loops -fomit-frame-pointer)
target_compile_options(p4oc_pool      PRIVATE -O3 -funroll-loops -fomit-frame-pointer)
target_compile_options(p4oc_memory    PRIVATE -O3 -funroll-loops -fomit-frame-pointer)
target_compile_options(p4oc_scroll    PRIVATE -O3 -ffast-math -funroll-loops -fomit-frame-pointer)

# NEON SIMD — ARMv7 only. arm64-v8a has NEON unconditionally and the v7a
# flags (-mfpu=neon -mfloat-abi=softfp) are invalid / rejected on AArch64.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(p4oc_animation PRIVATE -mfpu=neon -mfloat-abi=softfp)
endif()
