package com.jotheca.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Picks the rate-limiter backend from config: memory (default) or redis (multi-instance prod). */
@Configuration
public class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(@Value("${app.rate-limit.backend:memory}") String backend,
                            ObjectProvider<RedisConnectionFactory> redisFactory) {
        if ("redis".equalsIgnoreCase(backend)) {
            RedisConnectionFactory factory = redisFactory.getIfAvailable();
            if (factory == null) {
                throw new IllegalStateException(
                        "app.rate-limit.backend=redis requires spring.redis.* connection settings");
            }
            return new RedisRateLimiter(factory);
        }
        return new InMemoryRateLimiter();
    }
}
