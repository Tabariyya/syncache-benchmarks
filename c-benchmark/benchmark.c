#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <hiredis/hiredis.h>
#include <libpq-fe.h>
#include <synCache/cache_c.h>

#define WARMUP_N 5000
#define BENCH_N  100000

static const char VALUE[] = "{\"schema\":1,\"source\":\"syncache-benchmark\",";
static const size_t VALUE_LEN = sizeof(VALUE) - 1;

static const char *SEP = "-------------------------------------------------------------------";

// ─── Timing ───────────────────────────────────────────────────────────────────

typedef struct { double total_ms; double ops_per_sec; double avg_ns; } Stat;

static double now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
}

static Stat make_stat(int n, double t0, double t1) {
    double ns = t1 - t0;
    return (Stat){ ns / 1e6, n / (ns / 1e9), ns / n };
}

static void bkey(int i, char *buf, size_t buf_size) {
    snprintf(buf, buf_size, "bench:key:%d", i);
}

// ─── SynCache ops ─────────────────────────────────────────────────────────────

static Stat sc_set(int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        cache_set_string("bench", key, VALUE, NULL);
    }
    return make_stat(n, t0, now_ns());
}

static Stat sc_get(int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        char *val = cache_get_string("bench", key);
        cache_free(val);
    }
    return make_stat(n, t0, now_ns());
}

static Stat sc_evict(int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        cache_evict("bench", key);
    }
    return make_stat(n, t0, now_ns());
}

// ─── Redis ops ────────────────────────────────────────────────────────────────

static Stat rd_set(redisContext *rc, int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        freeReplyObject(redisCommand(rc, "SET %s %b", key, VALUE, VALUE_LEN));
    }
    return make_stat(n, t0, now_ns());
}

static Stat rd_get(redisContext *rc, int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        freeReplyObject(redisCommand(rc, "GET %s", key));
    }
    return make_stat(n, t0, now_ns());
}

static Stat rd_del(redisContext *rc, int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        freeReplyObject(redisCommand(rc, "DEL %s", key));
    }
    return make_stat(n, t0, now_ns());
}

// ─── PostgreSQL ops ───────────────────────────────────────────────────────────

static Stat pg_set(PGconn *pg, int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        const char *params[2] = { key, VALUE };
        PGresult *res = PQexecParams(pg,
            "INSERT INTO bench_kv(key, value) VALUES($1, $2) "
            "ON CONFLICT(key) DO UPDATE SET value = EXCLUDED.value",
            2, NULL, params, NULL, NULL, 0);
        PQclear(res);
    }
    return make_stat(n, t0, now_ns());
}

static Stat pg_get(PGconn *pg, int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        const char *params[1] = { key };
        PGresult *res = PQexecParams(pg,
            "SELECT value FROM bench_kv WHERE key = $1",
            1, NULL, params, NULL, NULL, 0);
        PQclear(res);
    }
    return make_stat(n, t0, now_ns());
}

static Stat pg_del(PGconn *pg, int n) {
    char key[32];
    double t0 = now_ns();
    for (int i = 0; i < n; ++i) {
        bkey(i, key, sizeof(key));
        const char *params[1] = { key };
        PGresult *res = PQexecParams(pg,
            "DELETE FROM bench_kv WHERE key = $1",
            1, NULL, params, NULL, NULL, 0);
        PQclear(res);
    }
    return make_stat(n, t0, now_ns());
}

// ─── Output ───────────────────────────────────────────────────────────────────

static void print_header(void) {
    printf("\n%-26s%11s%17s%16s\n%s\n",
           "Operation", "Total", "Throughput", "Avg latency", SEP);
}

static void print_row(const char *label, Stat s) {
    printf("%-26s%8.1f ms%13.0f ops/s%13.1f ns/op\n",
           label, s.total_ms, s.ops_per_sec, s.avg_ns);
}

static void print_speedup(const char *op,
                          const char *a_name, Stat a,
                          const char *b_name, Stat b) {
    double x = b.avg_ns / a.avg_ns;
    if (x >= 1.0)
        printf("  %s %s is %.1fx faster than %s\n", a_name, op, x, b_name);
    else
        printf("  %s %s is %.1fx faster than %s\n", b_name, op, 1.0 / x, a_name);
}

// ─── Main ─────────────────────────────────────────────────────────────────────

int main(void) {
    // ── Redis ──────────────────────────────────────────────────────────────────
    const char *host  = "host.docker.internal";
    int port = 23379;

    redisContext *rc = redisConnect(host, port);
    if (!rc || rc->err) {
        fprintf(stderr, "Redis connect failed: %s\n", rc ? rc->errstr : "OOM");
        if (rc) redisFree(rc);
        return 1;
    }
    freeReplyObject(redisCommand(rc, "SELECT 15"));
    freeReplyObject(redisCommand(rc, "FLUSHDB"));

    // ── PostgreSQL ─────────────────────────────────────────────────────────────
    const char *pg_host   ="host.docker.internal";
    const char *pg_port   = "49432";
    const char *pg_user   = "postgres";
    const char *pg_pass  = "postgres";
    const char *pg_dbname = "benchmark";

    char connstr[512];
    snprintf(connstr, sizeof(connstr),
             "host=%s port=%s user=%s password=%s dbname=%s connect_timeout=5",
             pg_host, pg_port, pg_user, pg_pass, pg_dbname);

    PGconn *pg = PQconnectdb(connstr);
    if (PQstatus(pg) != CONNECTION_OK) {
        fprintf(stderr, "PostgreSQL connect failed: %s", PQerrorMessage(pg));
        PQfinish(pg);
        redisFree(rc);
        return 1;
    }

    PQclear(PQexec(pg, "SET synchronous_commit = off"));
    PQclear(PQexec(pg,
        "CREATE TABLE IF NOT EXISTS bench_kv("
        "  key   TEXT PRIMARY KEY,"
        "  value TEXT NOT NULL)"));
    PQclear(PQexec(pg, "TRUNCATE bench_kv"));

    // ── SynCache ───────────────────────────────────────────────────────────────
    const char *tok = getenv("SYNCACHE_TOKEN");
    if (!tok || !*tok) {
        fprintf(stderr, "SYNCACHE_TOKEN env var is required.\n");
        PQfinish(pg);
        redisFree(rc);
        return 1;
    }

    cache_init(tok, 200000); // 2x BENCH_N entries
    sleep(1);

    // ── Banner ─────────────────────────────────────────────────────────────────
    printf("+----------------------------------------------------+\n"
           "|  SynCache vs Redis vs PostgreSQL  —  C Benchmark  |\n"
           "+----------------------------------------------------+\n"
           "  SynCache / Redis ops : %d\n"
           "  PostgreSQL ops       : %d\n"
           "  Value size           : %zu bytes\n"
           "  Redis                : %s:%d (DB 15)\n"
           "  PostgreSQL           : %s:%s  db=%s  table=bench_kv\n",
           BENCH_N, BENCH_N, VALUE_LEN,
           host, port, pg_host, pg_port, pg_dbname);

    // ── Warm-up ────────────────────────────────────────────────────────────────
    printf("\n  Warming up (%d ops each)... ", WARMUP_N);
    fflush(stdout);
    sc_set(WARMUP_N);
    sc_get(WARMUP_N);
    rd_set(rc, WARMUP_N);
    rd_get(rc, WARMUP_N);
    pg_set(pg, WARMUP_N);
    pg_get(pg, WARMUP_N);
    sleep(1);
    printf("done\n");

    // ── SET ────────────────────────────────────────────────────────────────────
    print_header();
    Stat sc_s = sc_set(BENCH_N); print_row("SynCache SET",   sc_s);
    Stat rd_s = rd_set(rc,    BENCH_N); print_row("Redis    SET",   rd_s);
    Stat pg_s = pg_set(pg,    BENCH_N); print_row("PostgreSQL SET", pg_s);
    printf("\n");

    // ── GET ────────────────────────────────────────────────────────────────────
    Stat sc_g = sc_get(BENCH_N); print_row("SynCache GET",   sc_g);
    Stat rd_g = rd_get(rc,    BENCH_N); print_row("Redis    GET",   rd_g);
    Stat pg_g = pg_get(pg,    BENCH_N); print_row("PostgreSQL GET", pg_g);
    printf("\n");

    // ── EVICT / DEL ────────────────────────────────────────────────────────────
    Stat sc_e = sc_evict(BENCH_N); print_row("SynCache EVICT", sc_e);
    Stat rd_e = rd_del(rc,      BENCH_N); print_row("Redis    DEL",   rd_e);
    Stat pg_e = pg_del(pg,      BENCH_N); print_row("PostgreSQL DEL", pg_e);

    printf("%s\n", SEP);

    // ── Summary ────────────────────────────────────────────────────────────────
    printf("\n  Results — SET:\n");
    print_speedup("SET", "SynCache", sc_s, "Redis",      rd_s);
    print_speedup("SET", "SynCache", sc_s, "PostgreSQL", pg_s);
    print_speedup("SET", "Redis",    rd_s, "PostgreSQL", pg_s);

    printf("\n  Results — GET:\n");
    print_speedup("GET", "SynCache", sc_g, "Redis",      rd_g);
    print_speedup("GET", "SynCache", sc_g, "PostgreSQL", pg_g);
    print_speedup("GET", "Redis",    rd_g, "PostgreSQL", pg_g);

    printf("\n  Results — EVICT/DEL:\n");
    print_speedup("EVICT", "SynCache", sc_e, "Redis",      rd_e);
    print_speedup("DEL",   "SynCache", sc_e, "PostgreSQL", pg_e);
    print_speedup("DEL",   "Redis",    rd_e, "PostgreSQL", pg_e);

    printf("\n  Notes:\n"
           "    - SynCache ops served from local in-process memory (%d ops)\n"
           "    - Redis requires a TCP round-trip per operation (%d ops)\n"
           "    - PostgreSQL is disk-backed with WAL, synchronous_commit=off (%d ops)\n"
           "    - All backends receive identical %zu-byte payloads\n\n",
           BENCH_N, BENCH_N, BENCH_N, VALUE_LEN);

    // ── Cleanup ────────────────────────────────────────────────────────────────
    freeReplyObject(redisCommand(rc, "FLUSHDB"));
    redisFree(rc);
    PQclear(PQexec(pg, "TRUNCATE bench_kv"));
    PQfinish(pg);
    return 0;
}
