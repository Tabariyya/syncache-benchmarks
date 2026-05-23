cmake_minimum_required(VERSION 3.28.3)

project(SynCacheExample CXX)

set(CMAKE_CXX_STANDARD 17)


set(SYNCACHE_VERSION "1.0.5")
set(_FETCH_SCRIPT "${CMAKE_BINARY_DIR}/FetchSynCache.cmake")
if(NOT EXISTS "${_FETCH_SCRIPT}")
    file(DOWNLOAD
        "https://github.com/Tabariyya/syncache-releases/releases/download/v${SYNCACHE_VERSION}/FetchSynCache.cmake"
        "${_FETCH_SCRIPT}"
        STATUS _DL_STATUS
    )
    list(GET _DL_STATUS 0 _DL_CODE)
    if(NOT _DL_CODE EQUAL 0)
        list(GET _DL_STATUS 1 _DL_MSG)
        message(FATAL_ERROR "[SynCache] Failed to download FetchSynCache.cmake: ${_DL_MSG}")
    endif()
endif()
include("${_FETCH_SCRIPT}")

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

add_executable(SynCacheBenchmark benchmark.cpp)
target_link_libraries(SynCacheBenchmark PRIVATE synCache::synCache hiredis::hiredis PostgreSQL::PostgreSQL)
