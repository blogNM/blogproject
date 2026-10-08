package com.blognm.mainblog.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** 서버 1대인 1차용 고정 창(fixed window) 카운터. */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> w.endsAt <= now);
        }
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || old.endsAt <= now) {
                return new Window(now + window.toMillis(), 1);
            }
            return new Window(old.endsAt, old.count + 1);
        });
        return w.count <= limit;
    }

    /** 테스트용: 모든 카운터를 지운다. */
    public void clear() {
        windows.clear();
    }

    private record Window(long endsAt, int count) {
    }
}
