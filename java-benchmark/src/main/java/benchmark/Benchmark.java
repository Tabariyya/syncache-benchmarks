package benchmark;

import com.tabariyya.synCache.Cache;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class Benchmark {

    private static final int WARMUP_N    = 5000;
    private static final int BENCH_N     = 10_000;
    private static final int NUM_THREADS = 8;

    // ── Value payloads ─────────────────────────────────────────────────────────

    private static String makeValue(int size) { return "x".repeat(size); }

    private static final String VALUE_100B = makeValue(100);
    private static final String VALUE_1KB  = makeValue(1_024);
    private static final String VALUE_10KB = makeValue(10_240);

    // ── Benchmark helpers ──────────────────────────────────────────────────────

    @FunctionalInterface interface BenchOp   { void run(int i)        throws Exception; }
    @FunctionalInterface interface BenchMtOp { void run(int t, int i) throws Exception; }

    /** Returns wall-clock milliseconds for n sequential calls to fn(i). */
    private static double benchST(BenchOp fn, int ops) throws Exception {
        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) fn.run(i);
        return (System.nanoTime() - t0) / 1e6;
    }

    /**
     * Returns wall-clock milliseconds for ops split across nThreads.
     * fn(t, i): t = thread index, i = per-thread index;
     * global index = t * (ops / nThreads) + i.
     */
    private static double benchMT(BenchMtOp fn, int ops, int nThreads) throws InterruptedException {
        int per = ops / nThreads;
        Thread[] workers = new Thread[nThreads];
        long t0 = System.nanoTime();
        for (int t = 0; t < nThreads; t++) {
            final int tid = t;
            workers[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < per; i++) fn.run(tid, i);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            workers[t].start();
        }
        for (Thread w : workers) w.join();
        return (System.nanoTime() - t0) / 1e6;
    }

    // ── Stats ──────────────────────────────────────────────────────────────────

    record Stat(double totalMs, double opsPerSec, double avgNs) {}

    private static Stat statFromMs(int n, double ms) {
        return new Stat(ms, n / ms * 1000.0, ms * 1e6 / n);
    }

    // ── Output ────────────────────────────────────────────────────────────────

    private static void benchPrintSection(String mode, String payload, int ops) {
        System.out.printf("%n------------------------------------------------------------------%n");
        System.out.printf("  %-30s | Payload: %-6s | Ops: %d%n", mode, payload, ops);
        System.out.printf("------------------------------------------------------------------%n");
        System.out.printf("  %-28s %10s  %14s  %14s%n",
            "Operation", "Total", "Throughput", "Avg latency");
        System.out.printf("  ------------------------------------------------------------------%n");
    }

    private static void benchPrintRow(String op, double ms, int ops) {
        double opsS = ops / ms * 1000.0;
        double nsOp = ms * 1e6 / ops;
        System.out.printf("  %-28s %8.1f ms  %10.0f ops/s  %12.1f ns/op%n", op, ms, opsS, nsOp);
    }

    private static void printSpeedup(String op, String aName, Stat a, String bName, Stat b) {
        double x = b.avgNs() / a.avgNs();
        if (x >= 1.0)
            System.out.printf("  %s %s is %.1fx faster than %s%n", aName, op, x, bName);
        else
            System.out.printf("  %s %s is %.1fx faster than %s%n", bName, op, 1.0 / x, aName);
    }

    record Suite(Stat scS, Stat scG, Stat scE,
                 Stat rdS, Stat rdG, Stat rdE,
                 Stat pgS, Stat pgG, Stat pgE) {}

    private static void printSuiteSummary(Suite s) {
        System.out.printf("  SET:%n");
        printSpeedup("SET",   "SynCache", s.scS(), "Redis",      s.rdS());
        printSpeedup("SET",   "SynCache", s.scS(), "PostgreSQL", s.pgS());
        printSpeedup("SET",   "Redis",    s.rdS(), "PostgreSQL", s.pgS());
        System.out.printf("  GET:%n");
        printSpeedup("GET",   "SynCache", s.scG(), "Redis",      s.rdG());
        printSpeedup("GET",   "SynCache", s.scG(), "PostgreSQL", s.pgG());
        printSpeedup("GET",   "Redis",    s.rdG(), "PostgreSQL", s.pgG());
        System.out.printf("  EVICT/DEL:%n");
        printSpeedup("EVICT", "SynCache", s.scE(), "Redis",      s.rdE());
        printSpeedup("DEL",   "SynCache", s.scE(), "PostgreSQL", s.pgE());
        printSpeedup("DEL",   "Redis",    s.rdE(), "PostgreSQL", s.pgE());
    }

    // ── Full comparison table ─────────────────────────────────────────────────

    private static String opsStr(double ops) {
        if      (ops >= 1e6) return String.format("%.2fM", ops / 1e6);
        else if (ops >= 1e3) return String.format("%.1fK", ops / 1e3);
        else                 return String.format("%.0f",  ops);
    }

    private static String centre(String s, int w) {
        int pad = w - s.length();
        if (pad <= 0) return s.substring(0, w);
        int l = pad / 2;
        return " ".repeat(l) + s + " ".repeat(pad - l);
    }

    // Layout mirrors the C++ table: label=17, data=11 → total 105 chars.
    private static void printFullTable(Suite[] allSt, Suite[] allMt) {
        final int LW     = 17;
        final int DW     = 11;
        final int MERGED = (DW + 2) * 2 + 1; // 27

        String hLabel = "-".repeat(LW + 2);
        String hData  = "-".repeat(DW + 2);
        String hPair  = "-".repeat(MERGED);

        String topSep = "+" + hLabel + (("+" + hPair).repeat(3)) + "+";
        String midSep = "+" + hLabel + (("+" + hData).repeat(6)) + "+";

        String[] sz = {"100 B", "1 KB", "10 KB"};

        // Size header row
        System.out.println(topSep);
        StringBuilder sizeRow = new StringBuilder("| ")
            .append(String.format("%-" + LW + "s", "")).append(" ");
        for (String s : sz) sizeRow.append("| ").append(centre(s, MERGED - 2)).append(" ");
        System.out.println(sizeRow.append("|"));

        // Thread-count sub-headers
        StringBuilder thRow = new StringBuilder("| ")
            .append(String.format("%-" + LW + "s", "Operation")).append(" ");
        for (int s = 0; s < 3; s++) {
            thRow.append("| ").append(centre("1 thread",  DW)).append(" ");
            thRow.append("| ").append(centre("8 threads", DW)).append(" ");
        }
        System.out.println(thRow.append("|"));

        // Data rows
        printTableRow(midSep, "SynCache SET",   DW, allSt[0].scS(), allMt[0].scS(), allSt[1].scS(), allMt[1].scS(), allSt[2].scS(), allMt[2].scS());
        printTableRow(null,   "Redis SET",      DW, allSt[0].rdS(), allMt[0].rdS(), allSt[1].rdS(), allMt[1].rdS(), allSt[2].rdS(), allMt[2].rdS());
        printTableRow(null,   "PostgreSQL SET", DW, allSt[0].pgS(), allMt[0].pgS(), allSt[1].pgS(), allMt[1].pgS(), allSt[2].pgS(), allMt[2].pgS());

        printTableRow(midSep, "SynCache GET",   DW, allSt[0].scG(), allMt[0].scG(), allSt[1].scG(), allMt[1].scG(), allSt[2].scG(), allMt[2].scG());
        printTableRow(null,   "Redis GET",      DW, allSt[0].rdG(), allMt[0].rdG(), allSt[1].rdG(), allMt[1].rdG(), allSt[2].rdG(), allMt[2].rdG());
        printTableRow(null,   "PostgreSQL GET", DW, allSt[0].pgG(), allMt[0].pgG(), allSt[1].pgG(), allMt[1].pgG(), allSt[2].pgG(), allMt[2].pgG());

        printTableRow(midSep, "SynCache EVICT", DW, allSt[0].scE(), allMt[0].scE(), allSt[1].scE(), allMt[1].scE(), allSt[2].scE(), allMt[2].scE());
        printTableRow(null,   "Redis DEL",      DW, allSt[0].rdE(), allMt[0].rdE(), allSt[1].rdE(), allMt[1].rdE(), allSt[2].rdE(), allMt[2].rdE());
        printTableRow(null,   "PostgreSQL DEL", DW, allSt[0].pgE(), allMt[0].pgE(), allSt[1].pgE(), allMt[1].pgE(), allSt[2].pgE(), allMt[2].pgE());

        System.out.println(midSep);
        System.out.printf("  Throughput in ops/s  (M = millions, K = thousands)%n");
        System.out.printf("  ST = single-threaded (1 thread),  MT = %d threads%n", NUM_THREADS);
    }

    private static void printTableRow(String sep, String label, int dw, Stat... stats) {
        if (sep != null) System.out.println(sep);
        final int LW = 17;
        StringBuilder row = new StringBuilder("| ")
            .append(String.format("%-" + LW + "s", label)).append(" ");
        for (Stat p : stats)
            row.append("| ").append(String.format("%" + dw + "s", opsStr(p.opsPerSec()))).append(" ");
        System.out.println(row.append("|"));
    }

    // ── Averages across all sizes & modes ──────────────────────────────────────

    private static void printAverages(Suite[] allSt, Suite[] allMt) {
        // 6 samples per backend per op: 3 sizes x {ST, MT}
        double scS = 0, rdS = 0, pgS = 0;
        double scG = 0, rdG = 0, pgG = 0;
        double scE = 0, rdE = 0, pgE = 0;
        for (int i = 0; i < 3; i++) {
            scS += allSt[i].scS().opsPerSec() + allMt[i].scS().opsPerSec();
            rdS += allSt[i].rdS().opsPerSec() + allMt[i].rdS().opsPerSec();
            pgS += allSt[i].pgS().opsPerSec() + allMt[i].pgS().opsPerSec();
            scG += allSt[i].scG().opsPerSec() + allMt[i].scG().opsPerSec();
            rdG += allSt[i].rdG().opsPerSec() + allMt[i].rdG().opsPerSec();
            pgG += allSt[i].pgG().opsPerSec() + allMt[i].pgG().opsPerSec();
            scE += allSt[i].scE().opsPerSec() + allMt[i].scE().opsPerSec();
            rdE += allSt[i].rdE().opsPerSec() + allMt[i].rdE().opsPerSec();
            pgE += allSt[i].pgE().opsPerSec() + allMt[i].pgE().opsPerSec();
        }
        scS /= 6; rdS /= 6; pgS /= 6;
        scG /= 6; rdG /= 6; pgG /= 6;
        scE /= 6; rdE /= 6; pgE /= 6;

        System.out.println();
        System.out.println("+----------------------------------------------------+");
        System.out.println("|   AVERAGES across 3 sizes x {ST, MT}  (6 samples)  |");
        System.out.println("+----------------------------------------------------+");
        System.out.printf("  %-18s %17s   %17s   %17s%n",
            "Operation", "SynCache", "Redis", "PostgreSQL");
        System.out.println("  ------------------------------------------------------------------------");
        printAvgRow("SET   (avg)",     scS, rdS, pgS);
        printAvgRow("GET   (avg)",     scG, rdG, pgG);
        printAvgRow("EVICT/DEL (avg)", scE, rdE, pgE);

        System.out.printf("%n  SET:%n");
        printAvgRatio("SET",   "SynCache", scS, "Redis",      rdS);
        printAvgRatio("SET",   "SynCache", scS, "PostgreSQL", pgS);
        printAvgRatio("SET",   "Redis",    rdS, "PostgreSQL", pgS);
        System.out.printf("  GET:%n");
        printAvgRatio("GET",   "SynCache", scG, "Redis",      rdG);
        printAvgRatio("GET",   "SynCache", scG, "PostgreSQL", pgG);
        printAvgRatio("GET",   "Redis",    rdG, "PostgreSQL", pgG);
        System.out.printf("  EVICT/DEL:%n");
        printAvgRatio("EVICT", "SynCache", scE, "Redis",      rdE);
        printAvgRatio("DEL",   "SynCache", scE, "PostgreSQL", pgE);
        printAvgRatio("DEL",   "Redis",    rdE, "PostgreSQL", pgE);
    }

    private static void printAvgRow(String label, double sc, double rd, double pg) {
        System.out.printf("  %-18s %12s ops/s   %12s ops/s   %12s ops/s%n",
            label, opsStr(sc), opsStr(rd), opsStr(pg));
    }

    private static void printAvgRatio(String op, String a, double av, String b, double bv) {
        double x = av / bv;
        if (x >= 1.0)
            System.out.printf("    %s %s is %.1fx faster than %s%n", a, op, x, b);
        else
            System.out.printf("    %s %s is %.1fx faster than %s%n", b, op, 1.0 / x, a);
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
        String pgHost = "host.docker.internal";
        String pgPort = "49432";
        String pgUser = "postgres";
        String pgPass = "postgres";
        String pgDb   = "benchmark";

        String pgUrl = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDb;
        java.util.Properties pgProps = new java.util.Properties();
        pgProps.setProperty("user",           pgUser);
        pgProps.setProperty("password",       pgPass);
        pgProps.setProperty("connectTimeout", "5");

        Connection pg = DriverManager.getConnection(pgUrl, pgProps);
        pg.createStatement().execute("SET synchronous_commit = off");
        pg.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS bench_kv(" +
            "  key TEXT PRIMARY KEY, value TEXT NOT NULL)");
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
            "  Ops per suite   : %d%n" +
            "  MT threads      : %d%n" +
            "  Value sizes     : 100 B  /  1 KB  /  10 KB%n" +
            "  Redis           : %s:%d (DB 15)%n" +
            "  PostgreSQL      : %s:%s  db=%s  table=bench_kv%n",
            BENCH_N, NUM_THREADS, redisHost, redisPort, pgHost, pgPort, pgDb);

        // ── Warm-up ───────────────────────────────────────────────────────────
        System.out.printf("%n  Warming up (%d ops each)... ", WARMUP_N);
        System.out.flush();
        {
            String[] wk = new String[WARMUP_N];
            for (int i = 0; i < WARMUP_N; i++) wk[i] = "bench:key:" + i;

            for (int i = 0; i < WARMUP_N; i++) cache.set("bench", wk[i], VALUE_1KB);
            for (int i = 0; i < WARMUP_N; i++) cache.get("bench", wk[i], String.class);

            for (int i = 0; i < WARMUP_N; i++) jedis.set(wk[i], VALUE_1KB);
            for (int i = 0; i < WARMUP_N; i++) jedis.get(wk[i]);

            PreparedStatement wSet = pg.prepareStatement(
                "INSERT INTO bench_kv(key,value) VALUES(?,?) " +
                "ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value");
            PreparedStatement wGet = pg.prepareStatement(
                "SELECT value FROM bench_kv WHERE key=?");
            for (int i = 0; i < WARMUP_N; i++) {
                wSet.setString(1, wk[i]); wSet.setString(2, VALUE_1KB); wSet.executeUpdate();
            }
            for (int i = 0; i < WARMUP_N; i++) {
                wGet.setString(1, wk[i]); wGet.executeQuery().close();
            }
            wSet.close(); wGet.close();

            jedis.flushDB();
            pg.createStatement().execute("TRUNCATE bench_kv");
        }
        Thread.sleep(500);
        System.out.println("done");

        // ── Run suites ────────────────────────────────────────────────────────
        String[] szLabels = {"100 B", "1 KB", "10 KB"};
        String[] szVals   = {VALUE_100B, VALUE_1KB, VALUE_10KB};
        Suite[]  allSt    = new Suite[3];
        Suite[]  allMt    = new Suite[3];

        for (int idx = 0; idx < 3; idx++) {
            String szLabel = szLabels[idx];
            String val     = szVals[idx];

            // Pre-generate keys — outside all timed sections
            String[] keys = new String[BENCH_N];
            for (int i = 0; i < BENCH_N; i++) keys[i] = "bench:key:" + i;

            // ── Single-threaded ────────────────────────────────────────────────
            benchPrintSection("SINGLE-THREADED", szLabel, BENCH_N);

            PreparedStatement psSet = pg.prepareStatement(
                "INSERT INTO bench_kv(key,value) VALUES(?,?) " +
                "ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value");
            PreparedStatement psGet = pg.prepareStatement(
                "SELECT value FROM bench_kv WHERE key=?");
            PreparedStatement psDel = pg.prepareStatement(
                "DELETE FROM bench_kv WHERE key=?");

            double ms;

            ms = benchST(i -> cache.set("bench", keys[i], val), BENCH_N);
            Stat scS = statFromMs(BENCH_N, ms); benchPrintRow("SynCache SET", ms, BENCH_N);

            ms = benchST(i -> jedis.set(keys[i], val), BENCH_N);
            Stat rdS = statFromMs(BENCH_N, ms); benchPrintRow("Redis    SET", ms, BENCH_N);

            ms = benchST(i -> { psSet.setString(1, keys[i]); psSet.setString(2, val); psSet.executeUpdate(); }, BENCH_N);
            Stat pgS = statFromMs(BENCH_N, ms); benchPrintRow("PostgreSQL SET", ms, BENCH_N);

            ms = benchST(i -> cache.get("bench", keys[i], String.class), BENCH_N);
            Stat scG = statFromMs(BENCH_N, ms); benchPrintRow("SynCache GET", ms, BENCH_N);

            ms = benchST(i -> jedis.get(keys[i]), BENCH_N);
            Stat rdG = statFromMs(BENCH_N, ms); benchPrintRow("Redis    GET", ms, BENCH_N);

            ms = benchST(i -> { psGet.setString(1, keys[i]); psGet.executeQuery().close(); }, BENCH_N);
            Stat pgG = statFromMs(BENCH_N, ms); benchPrintRow("PostgreSQL GET", ms, BENCH_N);

            ms = benchST(i -> cache.evict("bench", keys[i]), BENCH_N);
            Stat scE = statFromMs(BENCH_N, ms); benchPrintRow("SynCache EVICT", ms, BENCH_N);

            ms = benchST(i -> jedis.del(keys[i]), BENCH_N);
            Stat rdE = statFromMs(BENCH_N, ms); benchPrintRow("Redis    DEL", ms, BENCH_N);

            ms = benchST(i -> { psDel.setString(1, keys[i]); psDel.executeUpdate(); }, BENCH_N);
            Stat pgE = statFromMs(BENCH_N, ms); benchPrintRow("PostgreSQL DEL", ms, BENCH_N);

            psSet.close(); psGet.close(); psDel.close();
            allSt[idx] = new Suite(scS, scG, scE, rdS, rdG, rdE, pgS, pgG, pgE);
            System.out.printf("%n  Summary:%n");
            printSuiteSummary(allSt[idx]);

            // ── Multi-threaded ─────────────────────────────────────────────────
            // Pre-create per-thread connections so connection overhead is excluded
            // from the timed sections.
            Jedis[]             rdConns  = new Jedis[NUM_THREADS];
            Connection[]        pgConns  = new Connection[NUM_THREADS];
            PreparedStatement[] pgSetMt  = new PreparedStatement[NUM_THREADS];
            PreparedStatement[] pgGetMt  = new PreparedStatement[NUM_THREADS];
            PreparedStatement[] pgDelMt  = new PreparedStatement[NUM_THREADS];

            for (int t = 0; t < NUM_THREADS; t++) {
                rdConns[t] = new Jedis(redisHost, redisPort);
                rdConns[t].select(15);
                pgConns[t] = DriverManager.getConnection(pgUrl, pgProps);
                pgConns[t].createStatement().execute("SET synchronous_commit = off");
                pgSetMt[t] = pgConns[t].prepareStatement(
                    "INSERT INTO bench_kv(key,value) VALUES(?,?) " +
                    "ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value");
                pgGetMt[t] = pgConns[t].prepareStatement(
                    "SELECT value FROM bench_kv WHERE key=?");
                pgDelMt[t] = pgConns[t].prepareStatement(
                    "DELETE FROM bench_kv WHERE key=?");
            }

            jedis.flushDB();
            pg.createStatement().execute("TRUNCATE bench_kv");

            int per = BENCH_N / NUM_THREADS;
            benchPrintSection("MULTI-THREADED (" + NUM_THREADS + " threads)", szLabel, BENCH_N);

            ms = benchMT((t, i) -> cache.set("bench-mt", keys[t * per + i], val), BENCH_N, NUM_THREADS);
            scS = statFromMs(BENCH_N, ms); benchPrintRow("SynCache SET", ms, BENCH_N);

            ms = benchMT((t, i) -> rdConns[t].set(keys[t * per + i], val), BENCH_N, NUM_THREADS);
            rdS = statFromMs(BENCH_N, ms); benchPrintRow("Redis    SET", ms, BENCH_N);

            ms = benchMT((t, i) -> {
                pgSetMt[t].setString(1, keys[t * per + i]);
                pgSetMt[t].setString(2, val);
                pgSetMt[t].executeUpdate();
            }, BENCH_N, NUM_THREADS);
            pgS = statFromMs(BENCH_N, ms); benchPrintRow("PostgreSQL SET", ms, BENCH_N);

            ms = benchMT((t, i) -> cache.get("bench-mt", keys[t * per + i], String.class), BENCH_N, NUM_THREADS);
            scG = statFromMs(BENCH_N, ms); benchPrintRow("SynCache GET", ms, BENCH_N);

            ms = benchMT((t, i) -> rdConns[t].get(keys[t * per + i]), BENCH_N, NUM_THREADS);
            rdG = statFromMs(BENCH_N, ms); benchPrintRow("Redis    GET", ms, BENCH_N);

            ms = benchMT((t, i) -> {
                pgGetMt[t].setString(1, keys[t * per + i]);
                pgGetMt[t].executeQuery().close();
            }, BENCH_N, NUM_THREADS);
            pgG = statFromMs(BENCH_N, ms); benchPrintRow("PostgreSQL GET", ms, BENCH_N);

            ms = benchMT((t, i) -> cache.evict("bench-mt", keys[t * per + i]), BENCH_N, NUM_THREADS);
            scE = statFromMs(BENCH_N, ms); benchPrintRow("SynCache EVICT", ms, BENCH_N);

            ms = benchMT((t, i) -> rdConns[t].del(keys[t * per + i]), BENCH_N, NUM_THREADS);
            rdE = statFromMs(BENCH_N, ms); benchPrintRow("Redis    DEL", ms, BENCH_N);

            ms = benchMT((t, i) -> {
                pgDelMt[t].setString(1, keys[t * per + i]);
                pgDelMt[t].executeUpdate();
            }, BENCH_N, NUM_THREADS);
            pgE = statFromMs(BENCH_N, ms); benchPrintRow("PostgreSQL DEL", ms, BENCH_N);

            // Teardown per-thread connections
            for (int t = 0; t < NUM_THREADS; t++) {
                pgSetMt[t].close(); pgGetMt[t].close(); pgDelMt[t].close();
                pgConns[t].close();
                rdConns[t].close();
            }

            allMt[idx] = new Suite(scS, scG, scE, rdS, rdG, rdE, pgS, pgG, pgE);
            System.out.printf("%n  Summary:%n");
            printSuiteSummary(allMt[idx]);

            // Flush between sizes
            jedis.flushDB();
            pg.createStatement().execute("TRUNCATE bench_kv");
        }

        // ── Full comparison table ──────────────────────────────────────────────
        System.out.printf("%n%n");
        System.out.printf("+----------------------------------------------------+%n");
        System.out.printf("|         FULL COMPARISON TABLE  (ops/s)             |%n");
        System.out.printf("+----------------------------------------------------+%n%n");
        printFullTable(allSt, allMt);

        // ── Averages ──────────────────────────────────────────────────────────
        printAverages(allSt, allMt);

        // ── Cleanup ───────────────────────────────────────────────────────────
        System.out.println();
        jedis.flushDB();
        jedis.close();
        pg.createStatement().execute("TRUNCATE bench_kv");
        pg.close();
    }
}
