package com.example.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TokenBucketRateLimiter {
    private final int permitsPerSecond;
    private final AtomicLong nextAvailableTimeNanos = new AtomicLong(0L);

    public TokenBucketRateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    public void acquire() throws InterruptedException {
        long now = System.nanoTime();
        long waitNanos = 0L;
        long nextAvailableTime = nextAvailableTimeNanos.get();

        while (true) {
            if (nextAvailableTime <= now) {
                long newNextAvailableTime = now + TimeUnit.SECONDS.toNanos(1) / Math.max(1, permitsPerSecond);
                if (nextAvailableTimeNanos.compareAndSet(nextAvailableTime, newNextAvailableTime)) {
                    return;
                }
                nextAvailableTime = nextAvailableTimeNanos.get();
            } else {
                waitNanos = nextAvailableTime - now;
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(waitNanos));
                now = System.nanoTime();
                nextAvailableTime = nextAvailableTimeNanos.get();
            }
        }
    }
}
