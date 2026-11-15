# Host-side (Linux / macOS) tests of the Android Zenz runtime.
#
# They run the same C++ code that ships in libmozkey_zenz.so against the pinned
# llama.cpp and the Zenz model tracked in this repository, without an Android
# device. See README.md in this directory.

cmake_minimum_required(VERSION 3.22.1)
project(mozkey_zenz_host_tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(MOZKEY_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
set(MOZKEY_CPP_DIR "${MOZKEY_REPO_ROOT}/android/app/src/main/cpp")

if(NOT DEFINED MOZKEY_LLAMA_DIR)
  set(MOZKEY_LLAMA_DIR "${MOZKEY_REPO_ROOT}/android/third_party/llama.cpp")
endif()
if(NOT EXISTS "${MOZKEY_LLAMA_DIR}/CMakeLists.txt")
  message(FATAL_ERROR
    "llama.cpp was not found at ${MOZKEY_LLAMA_DIR}. "
    "Run android/scripts/prepare_native.sh llama first.")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory("${MOZKEY_LLAMA_DIR}" llama.cpp EXCLUDE_FROM_ALL)

# The llama.cpp backend on its own.
add_executable(backend_test
  backend_test.cc
  "${MOZKEY_CPP_DIR}/zenz_llama_backend.cc"
)
target_include_directories(backend_test PRIVATE "${MOZKEY_CPP_DIR}" "${MOZKEY_REPO_ROOT}/src")
target_link_libraries(backend_test PRIVATE llama)

# libmozc.so's ZenzInProcessClient -> C ABI -> backend. Needs Abseil sources:
#   cmake -DMOZKEY_ABSL_DIR=/path/to/abseil-cpp ...
if(DEFINED MOZKEY_ABSL_DIR)
  set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
  set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
  add_subdirectory("${MOZKEY_ABSL_DIR}" abseil-cpp EXCLUDE_FROM_ALL)
  add_executable(client_test
    client_test.cc
    "${MOZKEY_REPO_ROOT}/src/session/zenz_inprocess_client.cc"
    "${MOZKEY_CPP_DIR}/zenz_llama_backend.cc"
  )
  target_include_directories(client_test PRIVATE "${MOZKEY_CPP_DIR}" "${MOZKEY_REPO_ROOT}/src")
  target_link_libraries(client_test PRIVATE llama absl::time)
endif()
