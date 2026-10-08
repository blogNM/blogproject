package com.blognm.mainblog.auth;

import java.time.Clock;
import java.time.LocalDateTime;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 만료된 인증번호·Refresh Token과 오래된 로그인 실패 기록을 지운다 (4.5, SCL-03). */
@Component
public class AuthCleanupJob {

    private final EmailVerificationRepository verifications;
    private final RefreshTokenRepository refreshTokens;
    private final LoginFailureRepository failures;
    private final Clock clock;

    public AuthCleanupJob(EmailVerificationRepository verifications, RefreshTokenRepository refreshTokens,
            LoginFailureRepository failures, Clock clock) {
        this.verifications = verifications;
        this.refreshTokens = refreshTokens;
        this.failures = failures;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "authCleanup")
    @Transactional
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now(clock);
        verifications.deleteExpired(now);
        refreshTokens.deleteExpired(now);
        failures.deleteStale(now.minusDays(1), now);
    }
}
