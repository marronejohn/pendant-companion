cmake_minimum_required(VERSION 3.22)
project(pendantwhisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)


# whisper.cpp: library only, no examples/tests/server, CPU only
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

# libopus 1.5.2 (xiph, BSD-3-Clause): the decode engine for pendant audio.
# Library only: no programs, tests, or DRED/OSCE model dependencies.
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus)

add_library(pendantwhisper SHARED whisper_jni.cpp opus_jni.cpp)
target_link_libraries(pendantwhisper PRIVATE whisper ggml opus log)
