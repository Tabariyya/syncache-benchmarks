import io.github.WaleedSDA.synCache.CacheEntry;
import io.github.WaleedSDA.synCache.Controller;
import io.github.waleedsda.Person;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.LocalCachedMapOptions;
import org.redisson.api.RLocalCachedMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class BenchmarkTest {


    @Test
    public void syncCacheVsRedisson() {
        long synCacheExecutionTime = benchmarkSynCache();
        long redissonExecutionTime = benchmarkRedisson();

        System.out.println("=== 🕒 Cache Benchmark Results ===");
        System.out.printf("synCache : %d ms%n", synCacheExecutionTime);
        System.out.printf("Redisson :    %d ms%n", redissonExecutionTime);

        long diff = Math.abs(synCacheExecutionTime - redissonExecutionTime);
        double ratio = (double) Math.max(synCacheExecutionTime, redissonExecutionTime)
                / Math.min(synCacheExecutionTime, redissonExecutionTime);
        boolean redissonFaster = redissonExecutionTime < synCacheExecutionTime;

        System.out.println("-----------------------------------");
        System.out.printf("Difference: %d ms%n", diff);
        System.out.printf("Speed Ratio: %.2fx%n", ratio);
        System.out.println(redissonFaster
                ? "🚀 Redisson is faster!"
                : "🐢 synCache  is faster!");




    }


    public long benchmarkSynCache() {
        Controller ctrl = new Controller("amqp://guest:guest@91.93.135.176:25672/", 100, false);
        Person person = new Person("Waleed", 25);
        CacheEntry e = new CacheEntry("ns", "1", person, null);
        ctrl.set(e);
        for (int i = 0; i < 25000; i++) {
            ctrl.get("ns", "1", Person.class);
        }
        Instant start = Instant.now();
        for (int i = 0; i < 25000; i++) {
            ctrl.get("ns", "1", Person.class);
        }
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        return duration.toMillis();

    }

    long benchmarkRedisson() {
        Config config = new Config();
//        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        config.useSingleServer().setAddress("redis://host.docker.internal:6379");

        RedissonClient redisson = Redisson.create(config);

        // 2. Configure LocalCachedMap
        LocalCachedMapOptions<Object, Person> options = LocalCachedMapOptions.<Object, Person>defaults()
                .timeToLive(10, TimeUnit.SECONDS)
                .maxIdle(5, TimeUnit.SECONDS)
                .cacheSize(100)
                .syncStrategy(LocalCachedMapOptions.SyncStrategy.UPDATE);

        RLocalCachedMap<Object, Person> localCachedMap = redisson.getLocalCachedMap("myMap", options);

        // 3. Put data into cache
        Person person = new Person("Waleed", 25);


        localCachedMap.put("person:1", person);
        for (int i = 0; i < 25000; i++) {
            localCachedMap.get("person:1");
        }


        Instant start = Instant.now();
        for (int i = 0; i < 25000; i++) {
            localCachedMap.get("person:1");
        }

        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        redisson.shutdown();
        return duration.toMillis();
    }
}
