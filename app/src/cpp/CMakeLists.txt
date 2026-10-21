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

add_library(pendantwhisper SHARED whisper_jni.cpp)
target_link_libraries(pendantwhisper PRIVATE whisper ggml log)
