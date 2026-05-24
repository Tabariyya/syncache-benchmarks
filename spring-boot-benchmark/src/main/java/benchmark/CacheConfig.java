package benchmark;

import com.tabariyya.synCache.Cache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class CacheConfig {

    @Bean("cacheManager")
    @Primary
    public CacheManager synCacheManager(@Value("${SYNCACHE_TOKEN}") String token) {
        Cache sc = new Cache(token, 200_000);
        return new SynCacheCacheManager(sc);
    }

    @Bean("redisCacheManager")
    public CacheManager redisCacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration cfg = RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    RedisSerializer.string()));
        return RedisCacheManager.builder(factory).cacheDefaults(cfg).build();
    }

    // ── SynCache CacheManager ─────────────────────────────────────────────────

    private static class SynCacheCacheManager implements CacheManager {
        private final Cache sc;
        private final ConcurrentHashMap<String, org.springframework.cache.Cache> namespaces =
            new ConcurrentHashMap<>();

        SynCacheCacheManager(Cache sc) { this.sc = sc; }

        @Override
        public org.springframework.cache.Cache getCache(String name) {
            return namespaces.computeIfAbsent(name, n -> new SynCacheEntry(n, sc));
        }

        @Override
        public Collection<String> getCacheNames() { return namespaces.keySet(); }
    }

    private static class SynCacheEntry extends AbstractValueAdaptingCache {
        private final String namespace;
        private final Cache sc;

        SynCacheEntry(String namespace, Cache sc) {
            super(false);
            this.namespace = namespace;
            this.sc = sc;
        }

        @Override public String getName()        { return namespace; }
        @Override public Object getNativeCache() { return sc; }

        @Override
        protected Object lookup(Object key) {
            return sc.get(namespace, key.toString(), String.class);
        }

        @Override
        public void put(Object key, Object value) {
            if (value != null) sc.set(namespace, key.toString(), value.toString());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> valueLoader) {
            T cached = (T) sc.get(namespace, key.toString(), String.class);
            if (cached != null) return cached;
            try {
                T value = valueLoader.call();
                if (value != null) sc.set(namespace, key.toString(), value.toString());
                return value;
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }
        }

        @Override
        public void evict(Object key) {
            sc.evict(namespace, key.toString());
        }

        @Override
        public void clear() {}
    }
}
