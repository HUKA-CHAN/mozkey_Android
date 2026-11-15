# Builds libmozkey_zenz.so: llama.cpp + the in-process Zenz backend.
#
# libmozc.so itself is built by Bazel (see android/scripts/prepare_native.sh)
# and dropped into app/src/main/jniLibs as a prebuilt library.

cmake_minimum_required(VERSION 3.22.1)
project(mozkey_zenz LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# <repo>/android/app/src/main/cpp -> <repo>
get_filename_component(MOZKEY_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)

if(NOT DEFINED MOZKEY_LLAMA_DIR)
  set(MOZKEY_LLAMA_DIR "${MOZKEY_REPO_ROOT}/android/third_party/llama.cpp")
endif()
if(NOT EXISTS "${MOZKEY_LLAMA_DIR}/CMakeLists.txt")
  message(FATAL_ERROR
    "llama.cpp was not found at ${MOZKEY_LLAMA_DIR}. "
    "Run android/scripts/prepare_native.sh first.")
endif()

# Static, CPU-only llama.cpp without tools, server, curl/OpenSSL or OpenMP so
# that the APK ships a single self-contained libmozkey_zenz.so and needs no
# network code at all.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory("${MOZKEY_LLAMA_DIR}" llama.cpp EXCLUDE_FROM_ALL)

add_library(mozkey_zenz SHARED
  zenz_jni.cc
  zenz_llama_backend.cc
)

target_include_directories(mozkey_zenz PRIVATE
  "${MOZKEY_REPO_ROOT}/src"   # zenz/zenz_inprocess_abi.h
)

target_compile_options(mozkey_zenz PRIVATE -Wall -Wextra -fvisibility=hidden)

target_link_libraries(mozkey_zenz PRIVATE llama log dl)

# 16 KB page size support (required for targetSdk 35+ on 64-bit devices).
target_link_options(mozkey_zenz PRIVATE "-Wl,-z,max-page-size=16384")
