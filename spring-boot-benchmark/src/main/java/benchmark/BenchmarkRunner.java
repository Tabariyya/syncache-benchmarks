package benchmark;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class BenchmarkRunner implements CommandLineRunner {

    private static final int    WARMUP_N = 5000;
    private static final int    BENCH_N  = 100_000;
    private static final String VALUE    = "{\"schema\":1,\"source\":\"syncache-benchmark\",";
    private static final String SEP      = "-------------------------------------------------------------------";

    private final BenchmarkService svc;

    public BenchmarkRunner(BenchmarkService svc) {
        this.svc = svc;
    }

    record Stat(double totalMs, double opsPerSec, double avgNs) {}

    private static Stat makeStat(int n, long t0, long t1) {
        double ns = t1 - t0;
        return new Stat(ns / 1e6, n / (ns / 1e9), ns / n);
    }

    private static String bkey(int i) { return "bench:key:" + i; }

    // ── SynCache ──────────────────────────────────────────────────────────────

    private Stat scSet(int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) svc.scSet(bkey(i), VALUE);
        return makeStat(n, t0, System.nanoTime());
    }

    private Stat scGet(int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) svc.scGet(bkey(i));
        return makeStat(n, t0, System.nanoTime());
    }

    private Stat scEvict(int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) svc.scEvict(bkey(i));
        return makeStat(n, t0, System.nanoTime());
    }

    // ── Redis ─────────────────────────────────────────────────────────────────

    private Stat rdSet(int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) svc.rdSet(bkey(i), VALUE);
        return makeStat(n, t0, System.nanoTime());
    }

    private Stat rdGet(int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) svc.rdGet(bkey(i));
        return makeStat(n, t0, System.nanoTime());
    }

    private Stat rdEvict(int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) svc.rdEvict(bkey(i));
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

    @Override
    public void run(String... args) throws Exception {
        Thread.sleep(1000);

        System.out.printf(
            "+------------------------------------------------------------+%n" +
            "|   SynCache vs Redis — Spring Boot Annotation Benchmark     |%n" +
            "+------------------------------------------------------------+%n" +
            "  Operations : %d  (warm-up: %d)%n" +
            "  Value size : %d bytes%n" +
            "  Both backends use @CachePut / @Cacheable / @CacheEvict%n",
            BENCH_N, WARMUP_N, VALUE.length());

        // ── Warm-up ───────────────────────────────────────────────────────────
        System.out.printf("%n  Warming up (%d ops each)... ", WARMUP_N);
        System.out.flush();
        scSet(WARMUP_N); scGet(WARMUP_N);
        rdSet(WARMUP_N); rdGet(WARMUP_N);
        Thread.sleep(500);
        System.out.println("done");

        // ── SET ───────────────────────────────────────────────────────────────
        printHeader();
        Stat scS = scSet(BENCH_N); printRow("SynCache SET (@CachePut)", scS);
        Stat rdS = rdSet(BENCH_N); printRow("Redis    SET (@CachePut)", rdS);
        System.out.println();

        // ── GET (cache already populated by SET above) ────────────────────────
        Stat scG = scGet(BENCH_N); printRow("SynCache GET (@Cacheable)", scG);
        Stat rdG = rdGet(BENCH_N); printRow("Redis    GET (@Cacheable)", rdG);
        System.out.println();

        // ── EVICT ─────────────────────────────────────────────────────────────
        Stat scE = scEvict(BENCH_N); printRow("SynCache EVICT (@CacheEvict)", scE);
        Stat rdE = rdEvict(BENCH_N); printRow("Redis    EVICT (@CacheEvict)", rdE);

        System.out.println(SEP);

        // ── Summary ───────────────────────────────────────────────────────────
        System.out.println("\n  Results — SET:");
        printSpeedup("SET", "SynCache", scS, "Redis", rdS);

        System.out.println("\n  Results — GET:");
        printSpeedup("GET", "SynCache", scG, "Redis", rdG);

        System.out.println("\n  Results — EVICT:");
        printSpeedup("EVICT", "SynCache", scE, "Redis", rdE);

        System.out.printf(
            "%n  Notes:%n" +
            "    - SynCache reads served from local in-process memory%n" +
            "    - Redis requires a network round-trip per operation%n" +
            "    - Both use identical Spring Cache annotations with %d-byte payloads%n%n",
            VALUE.length());

        System.exit(0);
    }
}
