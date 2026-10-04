cmake_minimum_required(VERSION 3.22)

project("notifyme-llama" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# llama.cpp (v0.5.0, cloned to third_party/llama.cpp - not committed)
#
# Spike goals, in order of importance:
#  1. one self-contained .so per ABI with the CPU backend statically linked,
#     so loading needs no backend-dl path setup;
#  2. build for arm64-v8a (real phones) and x86_64 (MuMu emulator);
#  3. keep the matrix small: no CUDA, no OpenMP, no llamafile, no OpenSSL.
# ---------------------------------------------------------------------------

set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp"
        CACHE PATH "llama.cpp source root")

# Static llama + ggml; CPU backend registers statically (GGML_BACKEND_DL=OFF).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE    OFF CACHE BOOL "" FORCE)
set(GGML_CUDA         OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL     OFF CACHE BOOL "" FORCE)

# Subdirectory build is non-standalone: common utils default off, switch it on
# (we use common_sampler / common_chat_templates / common_batch helpers).
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        llama_jni.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        "${LLAMA_DIR}/include"
        "${LLAMA_DIR}/ggml/include"
        "${LLAMA_DIR}/common")

target_link_libraries(${CMAKE_PROJECT_NAME}
        PRIVATE
        llama
        llama-common
        android
        log)
