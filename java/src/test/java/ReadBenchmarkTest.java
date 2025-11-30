import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.waleedsda.Person;
import io.github.waleedsda.synCache.Controller;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.LocalCachedMapOptions;
import org.redisson.api.RLocalCachedMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class ReadBenchmarkTest {

    int PATCH_SIZE = 5_000_000;


    @BeforeEach
    public void clearRedis() {
        // Clear Redis before each benchmark
        RedisClient client = RedisClient.create("redis://redis:6379");
        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> commands = connection.sync();
        commands.flushdb();
        connection.close();
        client.shutdown();
    }

    @Test
    public void benchmarkSynCacheTest() throws InterruptedException {
        System.out.println("=== SynCache Benchmark ===");
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        Thread.sleep(10000);

        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        benchmarkSynCache();
        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsedMB = (memoryAfter - memoryBefore) / 1024.0 / 1024.0;

        System.out.printf("Approx. Memory Used: %.2f MB%n", memoryUsedMB);

    }


    @Test
    public void benchmarkRedissonTest() throws InterruptedException {
        System.out.println("=== Redisson Benchmark ===");
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        Thread.sleep(10000);

        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        benchmarkRedisson();
        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsedMB = (memoryAfter - memoryBefore) / 1024.0 / 1024.0;

        System.out.printf("Approx. Memory Used: %.2f MB%n", memoryUsedMB);

    }

    @Test
    public void benchmarkRedisTest() throws InterruptedException {
        System.out.println("=== Redis Benchmark ===");
        Runtime runtime = Runtime.getRuntime();

        System.gc();
        Thread.sleep(10000);

        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        benchmarkRedis();
        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsedMB = (memoryAfter - memoryBefore) / 1024.0 / 1024.0;

        System.out.printf("Approx. Memory Used: %.2f MB%n", memoryUsedMB);

    }


    public void benchmarkSynCache() {
        System.out.println("Start benchmark synCache");
        Controller ctrl = new Controller("ws://91.93.135.176:25672/", "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjI2MjgwMjc2MDUsInN1YiI6Imdvb2dsZSJ9.XgGmtV7ffxFI_a_g6U7lT_6mn2hc7RvJhO3lukUgMhRflgA5UwwHPt-5c5-uF_wsyA3HPmwQg_cjvI_JrG122OHqbC7Y-16059W-r4W_QALEgHHZKcijf_5g1CsG4DjGfHYJI4JmwrogQ0_yj4UUCD6OMY5v5g0QH4FCsxWcaI4", 100);

        Instant start = Instant.now();
        for (int i = 0; i < PATCH_SIZE; i++) {
            Person person = new Person("Waleed" + i, i);
            ctrl.set("ns", Integer.toString(i), person);
        }
        Instant end = Instant.now();
        System.out.printf("Write Execution Time: %d ms%n", Duration.between(start, end).toMillis());


        for (int i = 0; i < PATCH_SIZE; i++) {
            ctrl.get("ns", Integer.toString(i), Person.class);
        }

        start = Instant.now();
        for (int i = 0; i < PATCH_SIZE; i++) {
            ctrl.get("ns", Integer.toString(i), Person.class);
        }
        end = Instant.now();
        System.out.printf("Read Execution Time: %d ms%n", Duration.between(start, end).toMillis());


        start = Instant.now();
        for (int i = 0; i < PATCH_SIZE; i++) {
            ctrl.evict("ns", Integer.toString(i));
        }
        end = Instant.now();
        System.out.printf("Evict Execution Time: %d ms%n", Duration.between(start, end).toMillis());

    }

    public void benchmarkRedis() {
        System.out.println("Start benchmark redis");
        try {
            RedisClient client = RedisClient.create("redis://redis:6379");
            StatefulRedisConnection<String, String> connection = client.connect();
            RedisCommands<String, String> commands = connection.sync();
            ObjectMapper mapper = new ObjectMapper();
            Instant start = Instant.now();
            for (int i = 0; i < PATCH_SIZE; i++) {
                Person person = new Person("Waleed" + i, i);
                String json = mapper.writeValueAsString(person);
                commands.set("person:" + i, json);
            }
            Instant end = Instant.now();
            System.out.printf("Write Execution Time: %d ms%n", Duration.between(start, end).toMillis());


            for (int i = 0; i < PATCH_SIZE; i++) {
                String value = commands.get("person:" + i);
                mapper.readValue(value, Person.class);
            }

            start = Instant.now();
            for (int i = 0; i < PATCH_SIZE; i++) {
                String value = commands.get("person:" + i);
                mapper.readValue(value, Person.class);
            }
            end = Instant.now();
            System.out.printf("Read Execution Time: %d ms%n", Duration.between(start, end).toMillis());

            start = Instant.now();
            for (int i = 0; i < PATCH_SIZE; i++) {
                commands.del("person:" + i);
            }
            end = Instant.now();
            System.out.printf("Evict Execution Time: %d ms%n", Duration.between(start, end).toMillis());


            connection.close();
            client.shutdown();

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void benchmarkRedisson() {
        System.out.println("Start benchmark redisson");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://redis:6379");

        RedissonClient redisson = Redisson.create(config);
        LocalCachedMapOptions<Object, Person> options = LocalCachedMapOptions.<Object, Person>defaults().timeToLive(10, TimeUnit.SECONDS).maxIdle(5, TimeUnit.SECONDS).cacheSize(2_000_000).syncStrategy(LocalCachedMapOptions.SyncStrategy.UPDATE);

        RLocalCachedMap<Object, Person> localCachedMap = redisson.getLocalCachedMap("myMap", options);

        Instant start = Instant.now();
        for (int i = 0; i < PATCH_SIZE; i++) {
            Person person = new Person("Waleed" + i, i);
            localCachedMap.put("person:" + i, person);
        }
        Instant end = Instant.now();
        System.out.printf("Write Execution Time: %d ms%n", Duration.between(start, end).toMillis());

        for (int i = 0; i < PATCH_SIZE; i++) {
            localCachedMap.get("person:" + i);
        }

        start = Instant.now();
        for (int i = 0; i < PATCH_SIZE; i++) {
            localCachedMap.get("person:" + i);
        }
        end = Instant.now();
        System.out.printf("Read Execution Time: %d ms%n", Duration.between(start, end).toMillis());


        start = Instant.now();
        for (int i = 0; i < PATCH_SIZE; i++) {
            localCachedMap.remove("person:" + i);
        }
        end = Instant.now();
        System.out.printf("Evict Execution Time: %d ms%n", Duration.between(start, end).toMillis());

        redisson.getKeys().flushdb();
        redisson.shutdown();
    }


}
