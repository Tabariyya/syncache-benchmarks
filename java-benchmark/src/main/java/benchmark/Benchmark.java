package benchmark;

import com.tabariyya.synCache.Cache;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class Benchmark {

    private static final int WARMUP_N = 500;
    private static final int BENCH_N  = 100_000;
    private static final String VALUE  = "{\"schema\":1,\"source\":\"syncache-benchmark\",";
    private static final String SEP    = "-------------------------------------------------------------------";

    record Stat(double totalMs, double opsPerSec, double avgNs) {}

    private static Stat makeStat(int n, long t0, long t1) {
        double ns = t1 - t0;
        return new Stat(ns / 1e6, n / (ns / 1e9), ns / n);
    }

    private static String bkey(int i) {
        return "bench:key:" + i;
    }

    // ── SynCache ops ──────────────────────────────────────────────────────────

    private static Stat scSet(Cache cache, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++)
            cache.set("bench", bkey(i), VALUE);
        return makeStat(n, t0, System.nanoTime());
    }

    private static Stat scGet(Cache cache, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++)
            cache.get("bench", bkey(i), String.class);
        return makeStat(n, t0, System.nanoTime());
    }

    private static Stat scEvict(Cache cache, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++)
            cache.evict("bench", bkey(i));
        return makeStat(n, t0, System.nanoTime());
    }

    // ── Redis ops ─────────────────────────────────────────────────────────────

    private static Stat rdSet(Jedis jedis, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++)
            jedis.set(bkey(i), VALUE);
        return makeStat(n, t0, System.nanoTime());
    }

    private static Stat rdGet(Jedis jedis, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++)
            jedis.get(bkey(i));
        return makeStat(n, t0, System.nanoTime());
    }

    private static Stat rdDel(Jedis jedis, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++)
            jedis.del(bkey(i));
        return makeStat(n, t0, System.nanoTime());
    }

    // ── PostgreSQL ops ────────────────────────────────────────────────────────

    private static Stat pgSet(Connection pg, int n) throws SQLException {
        PreparedStatement ps = pg.prepareStatement(
            "INSERT INTO bench_kv(key, value) VALUES(?, ?) " +
            "ON CONFLICT(key) DO UPDATE SET value = EXCLUDED.value");
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ps.setString(1, bkey(i));
            ps.setString(2, VALUE);
            ps.executeUpdate();
        }
        ps.close();
        return makeStat(n, t0, System.nanoTime());
    }

    private static Stat pgGet(Connection pg, int n) throws SQLException {
        PreparedStatement ps = pg.prepareStatement(
            "SELECT value FROM bench_kv WHERE key = ?");
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ps.setString(1, bkey(i));
            ps.executeQuery().close();
        }
        ps.close();
        return makeStat(n, t0, System.nanoTime());
    }

    private static Stat pgDel(Connection pg, int n) throws SQLException {
        PreparedStatement ps = pg.prepareStatement(
            "DELETE FROM bench_kv WHERE key = ?");
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ps.setString(1, bkey(i));
            ps.executeUpdate();
        }
        ps.close();
        return makeStat(n, t0, System.nanoTime());
    }

    // ── Output ────────────────────────────────────────────────────────────────

    private static void printHeader() {
        System.out.printf("%n%-26s%11s%17s%16s%n%s%n",
            "Operation", "Total", "Throughput", "Avg latency", SEP);
    }

    private static void printRow(String label, Stat s) {
        System.out.printf("%-26s%8.1f ms%13.0f ops/s%13.1f ns/op%n",
            label, s.totalMs(), s.opsPerSec(), s.avgNs());
    }

    private static void printSpeedup(String op, String aName, Stat a, String bName, Stat b) {
        double x = b.avgNs() / a.avgNs();
        if (x >= 1.0)
            System.out.printf("  %s %s is %.1fx faster than %s%n", aName, op, x, bName);
        else
            System.out.printf("  %s %s is %.1fx faster than %s%n", bName, op, 1.0 / x, aName);
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        // ── Redis ─────────────────────────────────────────────────────────────
        String redisHost = "host.docker.internal";
        int    redisPort = 23379;

        Jedis jedis = new Jedis(redisHost, redisPort);
        jedis.select(15);
        jedis.flushDB();

        // ── PostgreSQL ────────────────────────────────────────────────────────
        String pgHost   = "host.docker.internal";
        String pgPort   = "49432";
        String pgUser   = "postgres";
        String pgPass   = "postgres";;
        String pgDb     = "benchmark";

        String pgUrl = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDb;
        java.util.Properties pgProps = new java.util.Properties();
        pgProps.setProperty("user", pgUser);
        pgProps.setProperty("password", pgPass);
        pgProps.setProperty("connectTimeout", "5");

        Connection pg = DriverManager.getConnection(pgUrl, pgProps);
        pg.createStatement().execute("SET synchronous_commit = off");
        pg.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS bench_kv(" +
            "  key   TEXT PRIMARY KEY," +
            "  value TEXT NOT NULL)");
        pg.createStatement().execute("TRUNCATE bench_kv");

        // ── SynCache ──────────────────────────────────────────────────────────
        String token = System.getenv("SYNCACHE_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("SYNCACHE_TOKEN env var is required.");
            System.exit(1);
        }

        Cache cache = new Cache(token, 200_000);
        Thread.sleep(1000);

        // ── Banner ────────────────────────────────────────────────────────────
        System.out.printf(
            "+----------------------------------------------------+%n" +
            "|  SynCache vs Redis vs PostgreSQL — Java Benchmark  |%n" +
            "+----------------------------------------------------+%n" +
            "  SynCache / Redis ops : %d%n" +
            "  PostgreSQL ops       : %d%n" +
            "  Value size           : %d bytes%n" +
            "  Redis                : %s:%d (DB 15)%n" +
            "  PostgreSQL           : %s:%s  db=%s  table=bench_kv%n",
            BENCH_N, BENCH_N, VALUE.length(),
            redisHost, redisPort, pgHost, pgPort, pgDb);

        // ── Warm-up ───────────────────────────────────────────────────────────
        System.out.printf("%n  Warming up (%d ops each)... ", WARMUP_N);
        System.out.flush();
        scSet(cache, WARMUP_N); scGet(cache, WARMUP_N);
        rdSet(jedis, WARMUP_N); rdGet(jedis, WARMUP_N);
        pgSet(pg,    WARMUP_N); pgGet(pg,    WARMUP_N);
        Thread.sleep(500);
        System.out.println("done");

        // ── SET ───────────────────────────────────────────────────────────────
        printHeader();
        Stat scS = scSet(cache, BENCH_N); printRow("SynCache SET",   scS);
        Stat rdS = rdSet(jedis, BENCH_N); printRow("Redis    SET",   rdS);
        Stat pgS = pgSet(pg,    BENCH_N); printRow("PostgreSQL SET", pgS);
        System.out.println();

        // ── GET ───────────────────────────────────────────────────────────────
        Stat scG = scGet(cache, BENCH_N); printRow("SynCache GET",   scG);
        Stat rdG = rdGet(jedis, BENCH_N); printRow("Redis    GET",   rdG);
        Stat pgG = pgGet(pg,    BENCH_N); printRow("PostgreSQL GET", pgG);
        System.out.println();

        // ── EVICT / DEL ───────────────────────────────────────────────────────
        Stat scE = scEvict(cache, BENCH_N); printRow("SynCache EVICT", scE);
        Stat rdE = rdDel(jedis,   BENCH_N); printRow("Redis    DEL",   rdE);
        Stat pgE = pgDel(pg,      BENCH_N); printRow("PostgreSQL DEL", pgE);

        System.out.println(SEP);

        // ── Summary ───────────────────────────────────────────────────────────
        System.out.println("\n  Results — SET:");
        printSpeedup("SET", "SynCache", scS, "Redis",      rdS);
        printSpeedup("SET", "SynCache", scS, "PostgreSQL", pgS);
        printSpeedup("SET", "Redis",    rdS, "PostgreSQL", pgS);

        System.out.println("\n  Results — GET:");
        printSpeedup("GET", "SynCache", scG, "Redis",      rdG);
        printSpeedup("GET", "SynCache", scG, "PostgreSQL", pgG);
        printSpeedup("GET", "Redis",    rdG, "PostgreSQL", pgG);

        System.out.println("\n  Results — EVICT/DEL:");
        printSpeedup("EVICT", "SynCache", scE, "Redis",      rdE);
        printSpeedup("DEL",   "SynCache", scE, "PostgreSQL", pgE);
        printSpeedup("DEL",   "Redis",    rdE, "PostgreSQL", pgE);

        System.out.printf(
            "%n  Notes:%n" +
            "    - SynCache ops served from local in-process memory (%d ops)%n" +
            "    - Redis requires a TCP round-trip per operation (%d ops)%n" +
            "    - PostgreSQL is disk-backed with WAL, synchronous_commit=off (%d ops)%n" +
            "    - All backends receive identical %d-byte payloads%n%n",
            BENCH_N, BENCH_N, BENCH_N, VALUE.length());

        // ── Cleanup ───────────────────────────────────────────────────────────
        jedis.flushDB();
        jedis.close();
        pg.createStatement().execute("TRUNCATE bench_kv");
        pg.close();
    }
}
