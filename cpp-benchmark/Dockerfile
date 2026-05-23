FROM ubuntu:24.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    libhiredis-dev \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*



WORKDIR /src
COPY CMakeLists.txt benchmark.cpp ./

RUN cmake -B build -DCMAKE_BUILD_TYPE=Release && \
    cmake --build build --parallel

ENTRYPOINT ["/src/build/SynCacheBenchmark"]

