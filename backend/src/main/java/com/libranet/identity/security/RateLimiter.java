package com.libranet.identity.security;

/** Brute-force throttle port. Implementations: in-memory (single node/dev) or Redis (distributed). */
public interface RateLimiter {

    /** @return true if the hit is allowed within limit-per-window, false when the budget is spent. */
    boolean tryAcquire(String key, int limit, long windowMillis);
}
