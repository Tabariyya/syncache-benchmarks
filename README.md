# SynCache Benchmarks

Head-to-head performance comparison of **SynCache** against **Redis** and **PostgreSQL** across set, get, and evict/delete operations. Two identical benchmarks are provided — one in C++, one in C — each producing the same output so results are directly comparable across languages.

## What is SynCache?

SynCache is an in-process cache with cross-instance synchronization. Unlike Redis or PostgreSQL, cache reads and writes happen entirely in local process memory — no network round-trip. A background WebSocket connection to the SynCache broker keeps all instances in sync.

Get your free token at **[syncache.tabariyya.com](https://syncache.tabariyya.com)**.

## Benchmark methodology

Each benchmark runs 100,000 operations per backend in three phases:

| Phase | SynCache | Redis | PostgreSQL |
|---|---|---|---|
| **SET** | `controller_set_string` / `c.set` | `SET` | `INSERT … ON CONFLICT DO UPDATE` |
| **GET** | `controller_get_string` / `c.getAsString` | `GET` | `SELECT` |
| **EVICT/DEL** | `controller_evict` / `c.evict` | `DEL` | `DELETE` |

A 500-operation warm-up runs before any timed measurement to bring connection pools, OS buffers, and the SynCache broker link to steady state.

### What makes this fair

- Redis and PostgreSQL are reached via the **host network** (`host.docker.internal`), not the Docker-internal bridge. This adds realistic loopback overhead equivalent to a same-host deployment, rather than the near-zero-latency Docker bridge.
- PostgreSQL runs with `fsync=off`, `synchronous_commit=off`, and `full_page_writes=off` — the fastest possible durable-ish configuration — to give it the best chance against an in-process cache.
- Redis runs with all persistence disabled (`--save "" --appendonly no`) and a high event-loop frequency.
- All three backends receive identical payloads.

## Prerequisites

- Docker and Docker Compose
- A SynCache token — get one free at [syncache.tabariyya.com](https://syncache.tabariyya.com)

## Setup

A `.env` file is already included at the root of the repository. The only value you need to replace is your SynCache token:

```
SYNCACHE_TOKEN=<replace_with_your_token>
```

Open `.env` and paste the token you received from [syncache.tabariyya.com](https://syncache.tabariyya.com).

## Running the benchmarks

### C++ benchmark

```bash
cd cpp-benchmark
docker compose up --build
```

### C benchmark

```bash
cd c-benchmark
docker compose up --build
```

### Java benchmark

```bash
cd java-benchmark
docker compose up --build
```

Both benchmarks self-download the SynCache library during the Docker build — no manual installation required. CMake fetches the correct pre-built binary for the target platform (Linux amd64 or arm64) from the [SynCache releases](https://github.com/Tabariyya/syncache-releases/releases).

## Example output

```
+----------------------------------------------------+
|  SynCache vs Redis vs PostgreSQL  —  Benchmark     |
+----------------------------------------------------+
  SynCache / Redis ops : 100000
  PostgreSQL ops       : 100000
  Value size           : 41 bytes

  Warming up (500 ops each)... done

Operation                      Total        Throughput     Avg latency
-------------------------------------------------------------------
SynCache SET                  312.4 ms      320205 ops/s      3124.1 ns/op
Redis    SET                 1843.7 ms       54234 ops/s     18437.2 ns/op
PostgreSQL SET               9128.1 ms       10955 ops/s     91281.4 ns/op

SynCache GET                   48.2 ms     2074688 ops/s        48.2 ns/op
Redis    GET                 1701.3 ms       58779 ops/s     17013.4 ns/op
PostgreSQL GET               7204.9 ms       13880 ops/s     72049.1 ns/op
...

  Results — GET:
  SynCache GET is 100x faster than Redis
  SynCache GET is 150x faster than PostgreSQL
```

## Repository structure

```
syncache-benchmarks/
├── cpp-benchmark/        C++ benchmark (uses synCache/Controller.hpp)
│   ├── benchmark.cpp
│   ├── CMakeLists.txt
│   ├── Dockerfile
│   └── docker-compose.yml
├── c-benchmark/          C benchmark (uses synCache/controller_c.h)
│   ├── benchmark.c
│   ├── CMakeLists.txt
│   ├── Dockerfile
│   └── docker-compose.yml
├── java-benchmark/       Java benchmark (uses com.tabariyya:syncache)
│   ├── src/main/java/benchmark/Benchmark.java
│   ├── pom.xml
│   ├── Dockerfile
│   └── docker-compose.yml
└── .env                  Your token and connection config (not committed)
```
