package com.jotheca.identity.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Distributed fixed-window limiter (Phase 8) for multi-instance deployments: INCR + PEXPIRE
 * on a key per (endpoint, ip, window). FAIL-OPEN by design if Redis is unreachable — the
 * durable account lockout + BCrypt cost still protect credentials, and an outage of the
 * cache must not become a login outage. The failure is logged loudly for alerting.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(RedisConnectionFactory factory) {
        this.redis = new StringRedisTemplate(factory);
    }

    @Override
    public boolean tryAcquire(String key, int limit, long windowMillis) {
        String windowKey = "rl:" + key + ":" + (System.currentTimeMillis() / windowMillis);
        try {
            Long count = redis.opsForValue().increment(windowKey);
            if (count != null && count == 1L) {
                redis.expire(windowKey, Duration.ofMillis(windowMillis * 2));
            }
            return count != null && count <= limit;
        } catch (Exception e) {
            log.error("rate-limiter backend unavailable — failing open for {}: {}", key, e.getMessage());
            return true;
        }
    }
}
