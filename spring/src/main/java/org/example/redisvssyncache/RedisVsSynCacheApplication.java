package org.example.redisvssyncache;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class RedisVsSynCacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisVsSynCacheApplication.class, args);
    }

}
