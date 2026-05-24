package benchmark;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class BenchmarkService {

    // ── SynCache (primary CacheManager from syncache-spring-boot-starter) ─────

    @CachePut(value = "bench", key = "#key")
    public String scSet(String key, String value) {
        return value;
    }

    @Cacheable(value = "bench", key = "#key")
    public String scGet(String key) {
        return null;
    }

    @CacheEvict(value = "bench", key = "#key")
    public void scEvict(String key) {}

    // ── Redis (explicit redisCacheManager bean) ───────────────────────────────

    @CachePut(value = "bench", key = "#key", cacheManager = "redisCacheManager")
    public String rdSet(String key, String value) {
        return value;
    }

    @Cacheable(value = "bench", key = "#key", cacheManager = "redisCacheManager")
    public String rdGet(String key) {
        return null;
    }

    @CacheEvict(value = "bench", key = "#key", cacheManager = "redisCacheManager")
    public void rdEvict(String key) {}
}
