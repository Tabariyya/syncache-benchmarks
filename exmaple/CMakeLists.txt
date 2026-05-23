cmake_minimum_required(VERSION 3.31.6)

project(SynCacheExample CXX)

set(CMAKE_CXX_STANDARD 17)
find_package(SynCache REQUIRED)



add_executable(SynCacheBenchmark benchmark.cpp)
target_link_libraries(SynCacheBenchmark PRIVATE synCache::synCache hiredis::hiredis PostgreSQL::PostgreSQL)
