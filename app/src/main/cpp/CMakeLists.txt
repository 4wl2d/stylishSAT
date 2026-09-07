cmake_minimum_required(VERSION 3.22.1)
project(stylish_whisper LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(FetchContent)
# Official whisper.cpp b4938, immutable commit; downloaded archive is checksum verified.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(whisper
    URL https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/371b5a7561823ab2bb32142d2751e35e7534727b
    URL_HASH SHA256=89051d8fca516a3ad1f5c2f8f9d2fccb089afbaec338fca3f8731999babc6f81
)
FetchContent_MakeAvailable(whisper)
# Tensor kernels need optimization even in the debuggable Android app. Leave JNI
# symbols/debuggability intact and use only baseline ABI instructions, not host-native flags.
foreach(runtime_target whisper ggml ggml-base ggml-cpu)
    if(TARGET ${runtime_target})
        target_compile_options(${runtime_target} PRIVATE $<$<CONFIG:Debug>:-O3>)
    endif()
endforeach()
add_library(stylish_whisper SHARED whisper_jni.cpp)
target_link_libraries(stylish_whisper PRIVATE whisper android log)
target_link_options(stylish_whisper PRIVATE "-Wl,-z,max-page-size=16384")
