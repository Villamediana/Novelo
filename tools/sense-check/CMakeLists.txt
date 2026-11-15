cmake_minimum_required(VERSION 3.22)
project(sense_check C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_DIR)
    message(FATAL_ERROR "LLAMA_DIR (llama.cpp checkout) is required")
endif()

# Only libllama, as the app has it (no tools, server or tests).
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} build-llama)

add_executable(sense-check sense_check.cpp)
target_include_directories(sense-check PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include)
target_link_libraries(sense-check PRIVATE llama ggml)
