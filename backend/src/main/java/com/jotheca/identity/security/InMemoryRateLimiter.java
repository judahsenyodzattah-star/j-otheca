package com.jotheca.identity.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window limiter for a single node (dev/test default). */
public class InMemoryRateLimiter implements RateLimiter {

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();
    private static final long SWEEP_INTERVAL_MS = 60_000L;
    private volatile long lastSweepMs = System.currentTimeMillis();

    @Override
    public boolean tryAcquire(String key, int limit, long windowMillis) {
        long now = System.currentTimeMillis();
        Deque<Long> hits = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > windowMillis) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                return false;
            }
            hits.addLast(now);
        }
        sweepIdle(now, windowMillis);
        return true;
    }

    /**
     * R3 fix: periodically drop keys whose sliding window has fully elapsed so idle keys cannot grow
     * the map without bound (memory-exhaustion DoS). Prunes each bucket with the current window, then
     * removes the ones left empty. Amortized O(n) at most once per SWEEP_INTERVAL_MS.
     */
    private void sweepIdle(long now, long windowMillis) {
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepMs = now;
        buckets.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            synchronized (d) {
                while (!d.isEmpty() && now - d.peekFirst() > windowMillis) {
                    d.pollFirst();
                }
                return d.isEmpty();
            }
        });
    }
}
