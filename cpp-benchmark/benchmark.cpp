#include <chrono>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <optional>
#include <string>
#include <thread>

#include <hiredis/hiredis.h>
#include <libpq-fe.h>
#include <synCache/Controller.hpp>


constexpr int WARMUP_N = 5000; // SynCache & Redis warm-up iterations
constexpr int BENCH_N = 100000; // SynCache & Redis (in-process / loopback)

static const std::string VALUE = [] {
    std::string v;
    v = R"({"schema":1,"source":"syncache-benchmark",)";
    return v;
}();

// ─── Timing ───────────────────────────────────────────────────────────────────

using Clock = std::chrono::high_resolution_clock;
using Nanos = std::chrono::duration<double, std::nano>;

struct Stat {
    double total_ms;
    double ops_per_sec;
    double avg_ns;
};

static Stat make_stat(int n, Clock::time_point t0, Clock::time_point t1) {
    double ns = Nanos(t1 - t0).count();
    return {ns / 1e6, n / (ns / 1e9), ns / n};
}

static std::string bkey(int i) {
    return "bench:key:" + std::to_string(i);
}

// ─── SynCache ops ─────────────────────────────────────────────────────────────

static Stat sc_set(Controller &c, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i)
        c.set("bench", bkey(i), VALUE, std::nullopt);
    return make_stat(n, t0, Clock::now());
}

static Stat sc_get(Controller &c, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i)
        (void) c.getAsString("bench", bkey(i));
    return make_stat(n, t0, Clock::now());
}

static Stat sc_evict(Controller &c, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i)
        c.evict("bench", bkey(i));
    return make_stat(n, t0, Clock::now());
}

// ─── Redis ops ────────────────────────────────────────────────────────────────

static Stat rd_set(redisContext *rc, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i) {
        auto k = bkey(i);
        freeReplyObject(static_cast<redisReply *>(
            redisCommand(rc, "SET %s %b", k.c_str(), VALUE.c_str(), VALUE.size())));
    }
    return make_stat(n, t0, Clock::now());
}

static Stat rd_get(redisContext *rc, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i) {
        auto k = bkey(i);
        freeReplyObject(static_cast<redisReply *>(
            redisCommand(rc, "GET %s", k.c_str())));
    }
    return make_stat(n, t0, Clock::now());
}

static Stat rd_del(redisContext *rc, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i) {
        auto k = bkey(i);
        freeReplyObject(static_cast<redisReply *>(
            redisCommand(rc, "DEL %s", k.c_str())));
    }
    return make_stat(n, t0, Clock::now());
}

// ─── PostgreSQL ops ───────────────────────────────────────────────────────────

static Stat pg_set(PGconn *pg, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i) {
        auto k = bkey(i);
        const char *params[2] = {k.c_str(), VALUE.c_str()};
        PGresult *res = PQexecParams(pg,
                                     "INSERT INTO bench_kv(key, value) VALUES($1, $2) "
                                     "ON CONFLICT(key) DO UPDATE SET value = EXCLUDED.value",
                                     2, nullptr, params, nullptr, nullptr, 0);
        PQclear(res);
    }
    return make_stat(n, t0, Clock::now());
}

static Stat pg_get(PGconn *pg, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i) {
        auto k = bkey(i);
        const char *params[1] = {k.c_str()};
        PGresult *res = PQexecParams(pg,
                                     "SELECT value FROM bench_kv WHERE key = $1",
                                     1, nullptr, params, nullptr, nullptr, 0);
        PQclear(res);
    }
    return make_stat(n, t0, Clock::now());
}

static Stat pg_del(PGconn *pg, int n) {
    auto t0 = Clock::now();
    for (int i = 0; i < n; ++i) {
        auto k = bkey(i);
        const char *params[1] = {k.c_str()};
        PGresult *res = PQexecParams(pg,
                                     "DELETE FROM bench_kv WHERE key = $1",
                                     1, nullptr, params, nullptr, nullptr, 0);
        PQclear(res);
    }
    return make_stat(n, t0, Clock::now());
}

// ─── Output ───────────────────────────────────────────────────────────────────

static const char *SEP = "-------------------------------------------------------------------";

static void print_header() {
    std::cout << "\n"
            << std::left << std::setw(26) << "Operation"
            << std::right << std::setw(11) << "Total"
            << std::setw(17) << "Throughput"
            << std::setw(16) << "Avg latency"
            << "\n" << SEP << "\n";
}

static void print_row(const std::string &label, const Stat &s) {
    std::cout << std::left << std::setw(26) << label
            << std::right
            << std::setw(8) << std::fixed << std::setprecision(1) << s.total_ms << " ms"
            << std::setw(13) << std::fixed << std::setprecision(0) << s.ops_per_sec << " ops/s"
            << std::setw(13) << std::fixed << std::setprecision(1) << s.avg_ns << " ns/op"
            << "\n";
}

static void print_speedup(const char *op,
                          const char *a_name, const Stat &a,
                          const char *b_name, const Stat &b) {
    double x = b.avg_ns / a.avg_ns;
    if (x >= 1.0)
        std::cout << "  " << a_name << " " << op << " is "
                << std::fixed << std::setprecision(1) << x
                << "x faster than " << b_name << "\n";
    else
        std::cout << "  " << b_name << " " << op << " is "
                << std::fixed << std::setprecision(1) << (1.0 / x)
                << "x faster than " << a_name << "\n";
}

// ─── Main ─────────────────────────────────────────────────────────────────────

int main() {
    // ── Redis ──────────────────────────────────────────────────────────────────
    const char *host = "host.docker.internal";
    int port = 23379;

    redisContext *rc = redisConnect(host, port);
    freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "SELECT 15")));
    freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "FLUSHDB")));

    // ── PostgreSQL ─────────────────────────────────────────────────────────────
    const char *pg_host = "host.docker.internal";
    const char *pg_port = "49432";
    const char *pg_user = "postgres";
    const char *pg_pass = "postgres";
    const char *pg_dbname = "benchmark";

    std::string connstr;
    connstr = "host=";
    connstr += pg_host;
    connstr += " port=";
    connstr += pg_port;
    connstr += " user=";
    connstr += pg_user;
    if (pg_pass && *pg_pass) {
        connstr += " password=";
        connstr += pg_pass;
    }
    connstr += " dbname=";
    connstr += pg_dbname;
    connstr += " connect_timeout=5";

    PGconn *pg = PQconnectdb(connstr.c_str());
    if (PQstatus(pg) != CONNECTION_OK) {
        std::cerr << "PostgreSQL connect failed: " << PQerrorMessage(pg)
                << "  Set PG_HOST / PG_PORT / PG_USER / PG_PASSWORD / PG_DBNAME.\n";
        PQfinish(pg);
        redisFree(rc);
        return 1;
    }

    // Async commits so WAL fsync doesn't dominate per-op latency
    {
        PGresult *r = PQexec(pg, "SET synchronous_commit = off");
        PQclear(r);
    } {
        PGresult *r = PQexec(pg,
                             "CREATE TABLE IF NOT EXISTS bench_kv("
                             "  key   TEXT PRIMARY KEY,"
                             "  value TEXT NOT NULL)");
        PQclear(r);
    } {
        PGresult *r = PQexec(pg, "TRUNCATE bench_kv");
        PQclear(r);
    }

    // ── SynCache ───────────────────────────────────────────────────────────────
    const char *tok = std::getenv("SYNCACHE_TOKEN");
    if (!tok || !*tok) {
        std::cerr << "SYNCACHE_TOKEN env var is required.\n";
        PQfinish(pg);
        redisFree(rc);
        return 1;
    }
    Controller cache(tok, 2000000);
    std::this_thread::sleep_for(std::chrono::milliseconds(500));

    // ── Banner ─────────────────────────────────────────────────────────────────
    std::cout << "+----------------------------------------------------+\n"
            << "|  SynCache vs Redis vs PostgreSQL  —  Benchmark     |\n"
            << "+----------------------------------------------------+\n"
            << "  SynCache / Redis ops : " << BENCH_N << "\n"
            << "  PostgreSQL ops       : " << BENCH_N << "\n"
            << "  Value size           : " << VALUE.size() << " bytes\n"
            << "  Redis                : " << host << ":" << port << " (DB 15)\n"
            << "  PostgreSQL           : " << pg_host << ":" << pg_port
            << "  db=" << pg_dbname << "  table=bench_kv\n";

    // ── Warm-up ────────────────────────────────────────────────────────────────
    std::cout << "\n  Warming up (" << WARMUP_N << " / " << WARMUP_N << " ops)... " << std::flush;
    sc_set(cache, WARMUP_N);
    sc_get(cache, WARMUP_N);
    rd_set(rc, WARMUP_N);
    rd_get(rc, WARMUP_N);
    pg_set(pg, WARMUP_N);
    pg_get(pg, WARMUP_N);
    std::cout << "done\n";

    // ── SET ────────────────────────────────────────────────────────────────────
    print_header();
    const Stat sc_s = sc_set(cache, BENCH_N);
    print_row("SynCache SET", sc_s);
    const Stat rd_s = rd_set(rc, BENCH_N);
    print_row("Redis    SET", rd_s);
    const Stat pg_s = pg_set(pg, BENCH_N);
    print_row("PostgreSQL SET", pg_s);
    std::cout << "\n";

    // ── GET ────────────────────────────────────────────────────────────────────
    const Stat sc_g = sc_get(cache, BENCH_N);
    print_row("SynCache GET", sc_g);
    const Stat rd_g = rd_get(rc, BENCH_N);
    print_row("Redis    GET", rd_g);
    const Stat pg_g = pg_get(pg, BENCH_N);
    print_row("PostgreSQL GET", pg_g);
    std::cout << "\n";

    // ── EVICT / DEL ────────────────────────────────────────────────────────────
    const Stat sc_e = sc_evict(cache, BENCH_N);
    print_row("SynCache EVICT", sc_e);
    const Stat rd_e = rd_del(rc, BENCH_N);
    print_row("Redis    DEL", rd_e);
    const Stat pg_e = pg_del(pg, BENCH_N);
    print_row("PostgreSQL DEL", pg_e);

    std::cout << SEP << "\n";

    // ── Summary ────────────────────────────────────────────────────────────────
    std::cout << "\n  Results — SET:\n";
    print_speedup("SET", "SynCache", sc_s, "Redis", rd_s);
    print_speedup("SET", "SynCache", sc_s, "PostgreSQL", pg_s);
    print_speedup("SET", "Redis", rd_s, "PostgreSQL", pg_s);

    std::cout << "\n  Results — GET:\n";
    print_speedup("GET", "SynCache", sc_g, "Redis", rd_g);
    print_speedup("GET", "SynCache", sc_g, "PostgreSQL", pg_g);
    print_speedup("GET", "Redis", rd_g, "PostgreSQL", pg_g);

    std::cout << "\n  Results — EVICT/DEL:\n";
    print_speedup("EVICT", "SynCache", sc_e, "Redis", rd_e);
    print_speedup("DEL", "SynCache", sc_e, "PostgreSQL", pg_e);
    print_speedup("DEL", "Redis", rd_e, "PostgreSQL", pg_e);

    std::cout << "\n"
            << "  Notes:\n"
            << "    - SynCache ops served from local in-process memory (" << BENCH_N << " ops)\n"
            << "    - Redis requires a TCP round-trip per operation (" << BENCH_N << " ops)\n"
            << "    - PostgreSQL is disk-backed with WAL, synchronous_commit=off (" << BENCH_N << " ops)\n"
            << "    - All backends receive identical " << VALUE.size() << "-byte payloads\n\n";

    // ── Cleanup ────────────────────────────────────────────────────────────────
    freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "FLUSHDB")));
    redisFree(rc); {
        PGresult *r = PQexec(pg, "TRUNCATE bench_kv");
        PQclear(r);
    }
    PQfinish(pg);
    return 0;
}
