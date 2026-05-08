cmake_minimum_required(VERSION 3.22.1)
project(jss_android)

# libnode.so + headers are vendored at build time by scripts/fetch-libnode.sh.
# We expect them at:
#   ../libnode/include          # public Node headers
#   ../libnode/bin/<abi>/libnode.so
set(LIBNODE_DIR ${CMAKE_SOURCE_DIR}/../../../../app/libnode)

# Pre-built libnode for the current ABI; gradle's externalNativeBuild handles
# the per-ABI invocation, so ANDROID_ABI is set per build.
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION ${LIBNODE_DIR}/bin/${ANDROID_ABI}/libnode.so)

# JNI shim — boots node::Start on a thread, pipes stdout/stderr to logcat.
add_library(native-lib SHARED native-lib.cpp)

target_include_directories(native-lib PRIVATE
    ${LIBNODE_DIR}/include/node)

find_library(log-lib log)

target_link_libraries(native-lib
    node
    ${log-lib})
