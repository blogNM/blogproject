package com.blognm.mainblog.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.blognm.mainblog.support.MutableClock;

/** 서버 메모리 요청 제한기 (D-80, D-83 보류). 횟수·시간 창·동시 요청·끝난 창 치우기를 확인한다. */
class InMemoryRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

    @Test
    @DisplayName("창 안에서는 정한 횟수까지만 되고, 창이 지나면 다시 센다. 키마다 따로 센다")
    void limitAndWindow() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a", 3, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("a", 3, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire("b", 3, Duration.ofMinutes(1))).isTrue();

        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAcquire("a", 3, Duration.ofMinutes(1))).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("a", 3, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    @DisplayName("동시에 200번 불러도 정한 10번만 통과한다")
    void concurrent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                calls.add(() -> limiter.tryAcquire("same", 10, Duration.ofMinutes(1)));
            }
            long passed = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                if (f.get()) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("키가 1만 개를 넘으면 끝난 창은 치운다")
    void cleansExpired() {
        for (int i = 0; i < 10_050; i++) {
            limiter.tryAcquire("k" + i, 1, Duration.ofSeconds(1));
        }
        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryAcquire("new", 1, Duration.ofSeconds(1))).isTrue();
        assertThat(limiter.size()).isLessThan(100);
    }
}
