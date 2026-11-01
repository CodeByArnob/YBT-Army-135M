cmake_minimum_required(VERSION 3.22.1)
project(ybt_native LANGUAGES C CXX)

include(FetchContent)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/dde879bc4f954b809892fd0d660515f42536394a.tar.gz
)
FetchContent_MakeAvailable(llama_cpp)

add_library(ybt_native SHARED native-lib.cpp)
target_compile_features(ybt_native PRIVATE cxx_std_17)
target_compile_options(ybt_native PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(ybt_native PRIVATE llama android log)
