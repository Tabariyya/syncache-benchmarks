#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <iomanip>
#include <iostream>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include <hiredis/hiredis.h>
#include <libpq-fe.h>
#include <synCache/Cache.hpp>

constexpr int WARMUP_N    = 5000;
constexpr int BENCH_N     = 10000;
constexpr int NUM_THREADS = 8;

// ─── Value payloads ───────────────────────────────────────────────────────────

static std::vector<uint8_t> make_value(size_t sz) { return std::vector<uint8_t>(sz, 'x'); }

static const std::vector<uint8_t> VALUE_100B = make_value(100);
static const std::vector<uint8_t> VALUE_1KB  = make_value(1024);
static const std::vector<uint8_t> VALUE_10KB = make_value(10240);

// ─── Benchmark helpers ────────────────────────────────────────────────────────

using Clock = std::chrono::high_resolution_clock;

// Returns wall-clock milliseconds for n sequential calls to fn(i).
static double benchST(const std::function<void(int)> &fn, int ops) {
    auto t0 = Clock::now();
    for (int i = 0; i < ops; ++i) fn(i);
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// Returns wall-clock milliseconds for ops calls split across nThreads.
// fn receives (thread_index, per-thread_index); global index = t*(ops/nThreads)+i.
static double benchMT(const std::function<void(int, int)> &fn, int ops, int nThreads) {
    int per = ops / nThreads;
    auto t0 = Clock::now();
    std::vector<std::thread> workers;
    workers.reserve(nThreads);
    for (int t = 0; t < nThreads; ++t)
        workers.emplace_back([&fn, t, per]() {
            for (int i = 0; i < per; ++i) fn(t, i);
        });
    for (auto &w : workers) w.join();
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// ─── Stats ────────────────────────────────────────────────────────────────────

struct Stat { double total_ms, ops_per_sec, avg_ns; };

static Stat stat_from_ms(int n, double ms) {
    return {ms, n / ms * 1000.0, ms * 1e6 / n};
}

// ─── Output ───────────────────────────────────────────────────────────────────

static void benchPrintSection(const char *mode, const char *payload, int ops) {
    std::printf("\n------------------------------------------------------------------\n");
    std::printf("  %-30s | Payload: %-6s | Ops: %d\n", mode, payload, ops);
    std::printf("------------------------------------------------------------------\n");
    std::printf("  %-28s %10s  %14s  %14s\n",
                "Operation", "Total", "Throughput", "Avg latency");
    std::printf("  ------------------------------------------------------------------\n");
}

static void benchPrintRow(const char *op, double ms, int ops) {
    double ops_s = ops / ms * 1000.0;
    double ns_op = ms * 1e6 / ops;
    std::printf("  %-28s %8.1f ms  %10.0f ops/s  %12.1f ns/op\n",
                op, ms, ops_s, ns_op);
}

static void print_speedup(const char *op,
                          const char *a_name, const Stat &a,
                          const char *b_name, const Stat &b) {
    double x = b.avg_ns / a.avg_ns;
    if (x >= 1.0)
        std::printf("  %s %s is %.1fx faster than %s\n", a_name, op, x, b_name);
    else
        std::printf("  %s %s is %.1fx faster than %s\n", b_name, op, 1.0 / x, a_name);
}

struct Suite { Stat sc_s, sc_g, sc_e, rd_s, rd_g, rd_e, pg_s, pg_g, pg_e; };

static void print_suite_summary(const Suite &s) {
    std::printf("  SET:\n");
    print_speedup("SET",   "SynCache", s.sc_s, "Redis",      s.rd_s);
    print_speedup("SET",   "SynCache", s.sc_s, "PostgreSQL", s.pg_s);
    print_speedup("SET",   "Redis",    s.rd_s, "PostgreSQL", s.pg_s);
    std::printf("  GET:\n");
    print_speedup("GET",   "SynCache", s.sc_g, "Redis",      s.rd_g);
    print_speedup("GET",   "SynCache", s.sc_g, "PostgreSQL", s.pg_g);
    print_speedup("GET",   "Redis",    s.rd_g, "PostgreSQL", s.pg_g);
    std::printf("  EVICT/DEL:\n");
    print_speedup("EVICT", "SynCache", s.sc_e, "Redis",      s.rd_e);
    print_speedup("DEL",   "SynCache", s.sc_e, "PostgreSQL", s.pg_e);
    print_speedup("DEL",   "Redis",    s.rd_e, "PostgreSQL", s.pg_e);
}

// ─── Full comparison table ────────────────────────────────────────────────────

static std::string ops_str(double ops) {
    char buf[32];
    if      (ops >= 1e6) std::snprintf(buf, sizeof(buf), "%.2fM", ops / 1e6);
    else if (ops >= 1e3) std::snprintf(buf, sizeof(buf), "%.1fK", ops / 1e3);
    else                 std::snprintf(buf, sizeof(buf), "%.0f",  ops);
    return buf;
}

static std::string ctr(const std::string &s, int w) {
    int pad = w - static_cast<int>(s.size());
    if (pad <= 0) return s.substr(0, w);
    int l = pad / 2;
    return std::string(l, ' ') + s + std::string(pad - l, ' ');
}

// Layout: label col inner width=17, data col inner width=11.
// Total row: 1+1+17+1 + (1+1+11+1)*6 + 1 = 20 + 84 + 1 = 105 chars.
static void print_full_table(const Suite all_st[3], const Suite all_mt[3]) {
    constexpr int LW     = 17;
    constexpr int DW     = 11;
    constexpr int MERGED = (DW + 2) * 2 + 1; // 27

    const std::string H_LABEL(LW + 2, '-');
    const std::string H_DATA (DW + 2, '-');
    const std::string H_PAIR (MERGED, '-');

    auto top_sep = [&]() {
        std::cout << "+" << H_LABEL;
        for (int i = 0; i < 3; ++i) std::cout << "+" << H_PAIR;
        std::cout << "+\n";
    };
    auto mid_sep = [&]() {
        std::cout << "+" << H_LABEL;
        for (int i = 0; i < 6; ++i) std::cout << "+" << H_DATA;
        std::cout << "+\n";
    };

    static const char *SZ[3] = {"100 B", "1 KB", "10 KB"};

    top_sep();
    std::cout << "| " << std::left << std::setw(LW) << "" << " ";
    for (int s = 0; s < 3; ++s)
        std::cout << "| " << ctr(SZ[s], MERGED - 2) << " ";
    std::cout << "|\n";

    std::cout << "| " << std::left << std::setw(LW) << "Operation" << " ";
    for (int s = 0; s < 3; ++s) {
        std::cout << "| " << ctr("1 thread",  DW) << " ";
        std::cout << "| " << ctr("8 threads", DW) << " ";
    }
    std::cout << "|\n";

    auto row = [&](const char *label,
                   const Stat &s0, const Stat &m0,
                   const Stat &s1, const Stat &m1,
                   const Stat &s2, const Stat &m2) {
        std::cout << "| " << std::left << std::setw(LW) << label << " ";
        for (const Stat *p : {&s0, &m0, &s1, &m1, &s2, &m2})
            std::cout << "| " << std::right << std::setw(DW) << ops_str(p->ops_per_sec) << " ";
        std::cout << "|\n";
    };

    mid_sep();
    row("SynCache SET",
        all_st[0].sc_s, all_mt[0].sc_s, all_st[1].sc_s, all_mt[1].sc_s,
        all_st[2].sc_s, all_mt[2].sc_s);
    row("Redis SET",
        all_st[0].rd_s, all_mt[0].rd_s, all_st[1].rd_s, all_mt[1].rd_s,
        all_st[2].rd_s, all_mt[2].rd_s);
    row("PostgreSQL SET",
        all_st[0].pg_s, all_mt[0].pg_s, all_st[1].pg_s, all_mt[1].pg_s,
        all_st[2].pg_s, all_mt[2].pg_s);

    mid_sep();
    row("SynCache GET",
        all_st[0].sc_g, all_mt[0].sc_g, all_st[1].sc_g, all_mt[1].sc_g,
        all_st[2].sc_g, all_mt[2].sc_g);
    row("Redis GET",
        all_st[0].rd_g, all_mt[0].rd_g, all_st[1].rd_g, all_mt[1].rd_g,
        all_st[2].rd_g, all_mt[2].rd_g);
    row("PostgreSQL GET",
        all_st[0].pg_g, all_mt[0].pg_g, all_st[1].pg_g, all_mt[1].pg_g,
        all_st[2].pg_g, all_mt[2].pg_g);

    mid_sep();
    row("SynCache EVICT",
        all_st[0].sc_e, all_mt[0].sc_e, all_st[1].sc_e, all_mt[1].sc_e,
        all_st[2].sc_e, all_mt[2].sc_e);
    row("Redis DEL",
        all_st[0].rd_e, all_mt[0].rd_e, all_st[1].rd_e, all_mt[1].rd_e,
        all_st[2].rd_e, all_mt[2].rd_e);
    row("PostgreSQL DEL",
        all_st[0].pg_e, all_mt[0].pg_e, all_st[1].pg_e, all_mt[1].pg_e,
        all_st[2].pg_e, all_mt[2].pg_e);

    mid_sep();
    std::printf("  Throughput in ops/s  (M = millions, K = thousands)\n");
    std::printf("  ST = single-threaded (1 thread),  MT = %d threads\n", NUM_THREADS);
}

// ─── Averages across all sizes & modes ────────────────────────────────────────

static void print_averages(const Suite all_st[3], const Suite all_mt[3]) {
    // 6 samples per backend per op: 3 sizes x {ST, MT}
    double sc_s = 0, rd_s = 0, pg_s = 0;
    double sc_g = 0, rd_g = 0, pg_g = 0;
    double sc_e = 0, rd_e = 0, pg_e = 0;
    for (int i = 0; i < 3; ++i) {
        sc_s += all_st[i].sc_s.ops_per_sec + all_mt[i].sc_s.ops_per_sec;
        rd_s += all_st[i].rd_s.ops_per_sec + all_mt[i].rd_s.ops_per_sec;
        pg_s += all_st[i].pg_s.ops_per_sec + all_mt[i].pg_s.ops_per_sec;
        sc_g += all_st[i].sc_g.ops_per_sec + all_mt[i].sc_g.ops_per_sec;
        rd_g += all_st[i].rd_g.ops_per_sec + all_mt[i].rd_g.ops_per_sec;
        pg_g += all_st[i].pg_g.ops_per_sec + all_mt[i].pg_g.ops_per_sec;
        sc_e += all_st[i].sc_e.ops_per_sec + all_mt[i].sc_e.ops_per_sec;
        rd_e += all_st[i].rd_e.ops_per_sec + all_mt[i].rd_e.ops_per_sec;
        pg_e += all_st[i].pg_e.ops_per_sec + all_mt[i].pg_e.ops_per_sec;
    }
    sc_s /= 6; rd_s /= 6; pg_s /= 6;
    sc_g /= 6; rd_g /= 6; pg_g /= 6;
    sc_e /= 6; rd_e /= 6; pg_e /= 6;

    auto row = [](const char *label, double sc, double rd, double pg) {
        std::printf("  %-18s %12s ops/s   %12s ops/s   %12s ops/s\n",
                    label,
                    ops_str(sc).c_str(), ops_str(rd).c_str(), ops_str(pg).c_str());
    };
    auto ratio = [](const char *op, const char *a, double av, const char *b, double bv) {
        double x = av / bv;
        if (x >= 1.0)
            std::printf("    %s %s is %.1fx faster than %s\n", a, op, x, b);
        else
            std::printf("    %s %s is %.1fx faster than %s\n", b, op, 1.0 / x, a);
    };

    std::printf("\n");
    std::printf("+----------------------------------------------------+\n");
    std::printf("|   AVERAGES across 3 sizes x {ST, MT}  (6 samples)  |\n");
    std::printf("+----------------------------------------------------+\n");
    std::printf("  %-18s %17s   %17s   %17s\n",
                "Operation", "SynCache", "Redis", "PostgreSQL");
    std::printf("  ------------------------------------------------------------------------\n");
    row("SET   (avg)", sc_s, rd_s, pg_s);
    row("GET   (avg)", sc_g, rd_g, pg_g);
    row("EVICT/DEL (avg)", sc_e, rd_e, pg_e);

    std::printf("\n  SET:\n");
    ratio("SET",   "SynCache", sc_s, "Redis",      rd_s);
    ratio("SET",   "SynCache", sc_s, "PostgreSQL", pg_s);
    ratio("SET",   "Redis",    rd_s, "PostgreSQL", pg_s);
    std::printf("  GET:\n");
    ratio("GET",   "SynCache", sc_g, "Redis",      rd_g);
    ratio("GET",   "SynCache", sc_g, "PostgreSQL", pg_g);
    ratio("GET",   "Redis",    rd_g, "PostgreSQL", pg_g);
    std::printf("  EVICT/DEL:\n");
    ratio("EVICT", "SynCache", sc_e, "Redis",      rd_e);
    ratio("DEL",   "SynCache", sc_e, "PostgreSQL", pg_e);
    ratio("DEL",   "Redis",    rd_e, "PostgreSQL", pg_e);
}

// ─── Main ─────────────────────────────────────────────────────────────────────

int main() {
    // ── Redis ──────────────────────────────────────────────────────────────────
    const std::string rd_host = "host.docker.internal";
    const int         rd_port = 23379;

    redisContext *rc = redisConnect(rd_host.c_str(), rd_port);
    freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "SELECT 15")));
    freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "FLUSHDB")));

    // ── PostgreSQL ─────────────────────────────────────────────────────────────
    const char *pg_host   = "host.docker.internal";
    const char *pg_port   = "49432";
    const char *pg_user   = "postgres";
    const char *pg_pass   = "postgres";
    const char *pg_dbname = "benchmark";

    std::string connstr;
    connstr  = "host=";    connstr += pg_host;
    connstr += " port=";   connstr += pg_port;
    connstr += " user=";   connstr += pg_user;
    if (pg_pass && *pg_pass) { connstr += " password="; connstr += pg_pass; }
    connstr += " dbname="; connstr += pg_dbname;
    connstr += " connect_timeout=5";

    PGconn *pg = PQconnectdb(connstr.c_str());
    if (PQstatus(pg) != CONNECTION_OK) {
        std::fprintf(stderr, "PostgreSQL connect failed: %s\n", PQerrorMessage(pg));
        PQfinish(pg); redisFree(rc); return 1;
    }
    PQclear(PQexec(pg, "SET synchronous_commit = off"));
    PQclear(PQexec(pg, "CREATE TABLE IF NOT EXISTS bench_kv("
                       "  key TEXT PRIMARY KEY, value TEXT NOT NULL)"));
    PQclear(PQexec(pg, "TRUNCATE bench_kv"));

    // ── SynCache ───────────────────────────────────────────────────────────────
    const char *tok = std::getenv("SYNCACHE_TOKEN");
    if (!tok || !*tok) {
        std::fprintf(stderr, "SYNCACHE_TOKEN env var is required.\n");
        PQfinish(pg); redisFree(rc); return 1;
    }
    Cache::initialize(tok, 2000000);
    Cache &cache = Cache::getInstance();
    std::this_thread::sleep_for(std::chrono::milliseconds(500));

    // ── Banner ─────────────────────────────────────────────────────────────────
    std::printf("+----------------------------------------------------+\n");
    std::printf("|  SynCache vs Redis vs PostgreSQL  —  Benchmark     |\n");
    std::printf("+----------------------------------------------------+\n");
    std::printf("  Ops per suite   : %d\n",  BENCH_N);
    std::printf("  MT threads      : %d\n",  NUM_THREADS);
    std::printf("  Value sizes     : 100 B  /  1 KB  /  10 KB\n");
    std::printf("  Redis           : %s:%d (DB 15)\n", rd_host.c_str(), rd_port);
    std::printf("  PostgreSQL      : %s:%s  db=%s  table=bench_kv\n",
                pg_host, pg_port, pg_dbname);

    // ── Warm-up ────────────────────────────────────────────────────────────────
    std::printf("\n  Warming up (%d ops each)... ", WARMUP_N);
    std::fflush(stdout);
    {
        // Pre-generate warmup keys
        std::vector<std::string> wk(WARMUP_N);
        for (int i = 0; i < WARMUP_N; ++i) wk[i] = "bench:key:" + std::to_string(i);
        const char *vptr = reinterpret_cast<const char *>(VALUE_1KB.data());


        for (int i = 0; i < WARMUP_N; ++i) cache.set("bench", wk[i], VALUE_1KB, std::nullopt);
        for (int i = 0; i < WARMUP_N; ++i) (void)cache.getRaw("bench", wk[i]);
        for (int i = 0; i < WARMUP_N; ++i) {
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rc, "SET %s %b", wk[i].c_str(), VALUE_1KB.data(), VALUE_1KB.size())));
        }
        for (int i = 0; i < WARMUP_N; ++i) {
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rc, "GET %s", wk[i].c_str())));
        }
        for (int i = 0; i < WARMUP_N; ++i) {
            const char *p[2] = {wk[i].c_str(), vptr};
            PQclear(PQexecParams(pg,
                "INSERT INTO bench_kv(key,value) VALUES($1,$2) "
                "ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value",
                2, nullptr, p, nullptr, nullptr, 0));
        }
        for (int i = 0; i < WARMUP_N; ++i) {
            const char *p[1] = {wk[i].c_str()};
            PQclear(PQexecParams(pg, "SELECT value FROM bench_kv WHERE key=$1",
                1, nullptr, p, nullptr, nullptr, 0));
        }
        cache.evict("bench");
        freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "FLUSHDB")));
        PQclear(PQexec(pg, "TRUNCATE bench_kv"));
    }
    std::printf("done\n");

    // ── Run suites ─────────────────────────────────────────────────────────────
    struct Config { const char *label; const std::vector<uint8_t> *val; };
    const Config configs[] = {
        {"100 B",  &VALUE_100B},
        {"1 KB",   &VALUE_1KB},
        {"10 KB",  &VALUE_10KB},
    };
    Suite all_st[3], all_mt[3];

    for (int idx = 0; idx < 3; ++idx) {
        const char *szLabel = configs[idx].label;
        const std::vector<uint8_t> &val = *configs[idx].val;
        const char *vptr = reinterpret_cast<const char *>(val.data());

        // Pre-generate keys — kept outside all timed sections
        std::vector<std::string> keys(BENCH_N);
        for (int i = 0; i < BENCH_N; ++i) keys[i] = "bench:key:" + std::to_string(i);

        // ── Single-threaded ────────────────────────────────────────────────────
        benchPrintSection("SINGLE-THREADED", szLabel, BENCH_N);

        // Warmup this size then clear
        for (int i = 0; i < std::min(BENCH_N / 10, 1000); ++i)
            cache.set("bench", keys[i], val, std::nullopt);
        cache.evict("bench");

        double ms;
        Suite &st = all_st[idx];

        ms = benchST([&](int i) {
            cache.set("bench", keys[i], val, std::nullopt);
        }, BENCH_N);
        st.sc_s = stat_from_ms(BENCH_N, ms);
        benchPrintRow("SynCache SET", ms, BENCH_N);

        ms = benchST([&](int i) {
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rc, "SET %s %b", keys[i].c_str(), val.data(), val.size())));
        }, BENCH_N);
        st.rd_s = stat_from_ms(BENCH_N, ms);
        benchPrintRow("Redis    SET", ms, BENCH_N);

        ms = benchST([&](int i) {
            const char *p[2] = {keys[i].c_str(), vptr};
            PQclear(PQexecParams(pg,
                "INSERT INTO bench_kv(key,value) VALUES($1,$2) "
                "ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value",
                2, nullptr, p, nullptr, nullptr, 0));
        }, BENCH_N);
        st.pg_s = stat_from_ms(BENCH_N, ms);
        benchPrintRow("PostgreSQL SET", ms, BENCH_N);

        ms = benchST([&](int i) {
            (void)cache.getRaw("bench", keys[i]);
        }, BENCH_N);
        st.sc_g = stat_from_ms(BENCH_N, ms);
        benchPrintRow("SynCache GET", ms, BENCH_N);

        ms = benchST([&](int i) {
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rc, "GET %s", keys[i].c_str())));
        }, BENCH_N);
        st.rd_g = stat_from_ms(BENCH_N, ms);
        benchPrintRow("Redis    GET", ms, BENCH_N);

        ms = benchST([&](int i) {
            const char *p[1] = {keys[i].c_str()};
            PQclear(PQexecParams(pg, "SELECT value FROM bench_kv WHERE key=$1",
                1, nullptr, p, nullptr, nullptr, 0));
        }, BENCH_N);
        st.pg_g = stat_from_ms(BENCH_N, ms);
        benchPrintRow("PostgreSQL GET", ms, BENCH_N);

        ms = benchST([&](int i) {
            cache.evict("bench", keys[i]);
        }, BENCH_N);
        st.sc_e = stat_from_ms(BENCH_N, ms);
        benchPrintRow("SynCache EVICT", ms, BENCH_N);

        ms = benchST([&](int i) {
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rc, "DEL %s", keys[i].c_str())));
        }, BENCH_N);
        st.rd_e = stat_from_ms(BENCH_N, ms);
        benchPrintRow("Redis    DEL", ms, BENCH_N);

        ms = benchST([&](int i) {
            const char *p[1] = {keys[i].c_str()};
            PQclear(PQexecParams(pg, "DELETE FROM bench_kv WHERE key=$1",
                1, nullptr, p, nullptr, nullptr, 0));
        }, BENCH_N);
        st.pg_e = stat_from_ms(BENCH_N, ms);
        benchPrintRow("PostgreSQL DEL", ms, BENCH_N);

        std::printf("\n  Summary:\n");
        print_suite_summary(st);

        // ── Multi-threaded ─────────────────────────────────────────────────────
        // Pre-create per-thread connections so connection overhead is excluded
        // from the timed section.
        std::vector<redisContext *> rd_conns(NUM_THREADS);
        for (int t = 0; t < NUM_THREADS; ++t) {
            rd_conns[t] = redisConnect(rd_host.c_str(), rd_port);
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rd_conns[t], "SELECT 15")));
        }
        std::vector<PGconn *> pg_conns(NUM_THREADS);
        for (int t = 0; t < NUM_THREADS; ++t) {
            pg_conns[t] = PQconnectdb(connstr.c_str());
            PQclear(PQexec(pg_conns[t], "SET synchronous_commit = off"));
        }

        freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "FLUSHDB")));
        PQclear(PQexec(pg, "TRUNCATE bench_kv"));

        std::string mtLabel = "MULTI-THREADED (" + std::to_string(NUM_THREADS) + " threads)";
        benchPrintSection(mtLabel.c_str(), szLabel, BENCH_N);

        Suite &mt = all_mt[idx];
        int per = BENCH_N / NUM_THREADS;

        ms = benchMT([&](int t, int i) {
            cache.set("bench-mt", keys[t * per + i], val, std::nullopt);
        }, BENCH_N, NUM_THREADS);
        mt.sc_s = stat_from_ms(BENCH_N, ms);
        benchPrintRow("SynCache SET", ms, BENCH_N);

        ms = benchMT([&](int t, int i) {
            const auto &k = keys[t * per + i];
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rd_conns[t], "SET %s %b", k.c_str(), val.data(), val.size())));
        }, BENCH_N, NUM_THREADS);
        mt.rd_s = stat_from_ms(BENCH_N, ms);
        benchPrintRow("Redis    SET", ms, BENCH_N);

        ms = benchMT([&](int t, int i) {
            const auto &k = keys[t * per + i];
            const char *p[2] = {k.c_str(), vptr};
            PQclear(PQexecParams(pg_conns[t],
                "INSERT INTO bench_kv(key,value) VALUES($1,$2) "
                "ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value",
                2, nullptr, p, nullptr, nullptr, 0));
        }, BENCH_N, NUM_THREADS);
        mt.pg_s = stat_from_ms(BENCH_N, ms);
        benchPrintRow("PostgreSQL SET", ms, BENCH_N);

        ms = benchMT([&](int t, int i) {
            (void)cache.getRaw("bench-mt", keys[t * per + i]);
        }, BENCH_N, NUM_THREADS);
        mt.sc_g = stat_from_ms(BENCH_N, ms);
        benchPrintRow("SynCache GET", ms, BENCH_N);

        ms = benchMT([&](int t, int i) {
            const auto &k = keys[t * per + i];
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rd_conns[t], "GET %s", k.c_str())));
        }, BENCH_N, NUM_THREADS);
        mt.rd_g = stat_from_ms(BENCH_N, ms);
        benchPrintRow("Redis    GET", ms, BENCH_N);

        ms = benchMT([&](int t, int i) {
            const auto &k = keys[t * per + i];
            const char *p[1] = {k.c_str()};
            PQclear(PQexecParams(pg_conns[t], "SELECT value FROM bench_kv WHERE key=$1",
                1, nullptr, p, nullptr, nullptr, 0));
        }, BENCH_N, NUM_THREADS);
        mt.pg_g = stat_from_ms(BENCH_N, ms);
        benchPrintRow("PostgreSQL GET", ms, BENCH_N);

        ms = benchMT([&](int t, int i) {
            cache.evict("bench-mt", keys[t * per + i]);
        }, BENCH_N, NUM_THREADS);
        mt.sc_e = stat_from_ms(BENCH_N, ms);
        benchPrintRow("SynCache EVICT", ms, BENCH_N);

        ms = benchMT([&](int t, int i) {
            const auto &k = keys[t * per + i];
            freeReplyObject(static_cast<redisReply *>(
                redisCommand(rd_conns[t], "DEL %s", k.c_str())));
        }, BENCH_N, NUM_THREADS);
        mt.rd_e = stat_from_ms(BENCH_N, ms);
        benchPrintRow("Redis    DEL", ms, BENCH_N);

        ms = benchMT([&](int t, int i) {
            const auto &k = keys[t * per + i];
            const char *p[1] = {k.c_str()};
            PQclear(PQexecParams(pg_conns[t], "DELETE FROM bench_kv WHERE key=$1",
                1, nullptr, p, nullptr, nullptr, 0));
        }, BENCH_N, NUM_THREADS);
        mt.pg_e = stat_from_ms(BENCH_N, ms);
        benchPrintRow("PostgreSQL DEL", ms, BENCH_N);

        std::printf("\n  Summary:\n");
        print_suite_summary(mt);

        // Teardown per-thread connections
        for (auto *c : rd_conns) redisFree(c);
        for (auto *c : pg_conns) PQfinish(c);

        cache.evict();
        freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "FLUSHDB")));
        PQclear(PQexec(pg, "TRUNCATE bench_kv"));
    }

    // ── Full comparison table ──────────────────────────────────────────────────
    std::printf("\n\n");
    std::printf("+----------------------------------------------------+\n");
    std::printf("|         FULL COMPARISON TABLE  (ops/s)             |\n");
    std::printf("+----------------------------------------------------+\n\n");
    print_full_table(all_st, all_mt);

    // ── Averages ───────────────────────────────────────────────────────────────
    print_averages(all_st, all_mt);

    // ── Cleanup ────────────────────────────────────────────────────────────────
    std::printf("\n");
    freeReplyObject(static_cast<redisReply *>(redisCommand(rc, "FLUSHDB")));
    redisFree(rc);
    PQclear(PQexec(pg, "TRUNCATE bench_kv"));
    PQfinish(pg);
    return 0;
}
