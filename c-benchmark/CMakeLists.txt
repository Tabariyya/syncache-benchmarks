cmake_minimum_required(VERSION 3.28.3)

project(SynCacheCBenchmark C CXX)

set(CMAKE_C_STANDARD 11)

set(SYNCACHE_VERSION "1.0.5")
file(DOWNLOAD
    "https://github.com/Tabariyya/syncache-releases/releases/download/v${SYNCACHE_VERSION}/FetchSynCache.cmake"
    "${CMAKE_BINARY_DIR}/FetchSynCache.cmake")
include("${CMAKE_BINARY_DIR}/FetchSynCache.cmake")

find_package(PostgreSQL REQUIRED)

find_path(HIREDIS_INCLUDE_DIR hiredis/hiredis.h)
find_library(HIREDIS_LIB hiredis)
if(NOT HIREDIS_INCLUDE_DIR OR NOT HIREDIS_LIB)
    message(FATAL_ERROR "hiredis not found — install libhiredis-dev")
endif()
add_library(hiredis::hiredis UNKNOWN IMPORTED)
set_target_properties(hiredis::hiredis PROPERTIES
    IMPORTED_LOCATION "${HIREDIS_LIB}"
    INTERFACE_INCLUDE_DIRECTORIES "${HIREDIS_INCLUDE_DIR}"
)

add_executable(SynCacheCBenchmark benchmark.c)
# C++ linker needed because synCache is a C++ library under the hood
set_target_properties(SynCacheCBenchmark PROPERTIES LINKER_LANGUAGE CXX)
target_link_libraries(SynCacheCBenchmark PRIVATE synCache::synCache hiredis::hiredis PostgreSQL::PostgreSQL)
