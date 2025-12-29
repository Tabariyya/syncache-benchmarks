FROM python:3.13-slim AS builder

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    g++ \
    cmake \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY requirement.txt .
RUN pip install -r requirement.txt

COPY src .

CMD ["python", "app.py"]