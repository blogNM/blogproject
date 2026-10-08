package com.blognm.mainblog.auth;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 이메일별 로그인 실패 횟수와 잠금 (SEC-03, SEC-13). 가입 안 된 이메일도 같게 센다. */
@Entity
@Table(name = "login_failures")
public class LoginFailure {

    @Id
    private String email;

    @Column(name = "fail_count")
    @JdbcTypeCode(SqlTypes.TINYINT)
    private int failCount;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_failed_at")
    private LocalDateTime lastFailedAt;

    protected LoginFailure() {
    }

    public LoginFailure(String email) {
        this.email = email;
    }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 잠금 시간이 지났으면 처음부터 다시 센다. */
    public void resetIfLockExpired(LocalDateTime now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            failCount = 0;
            lockedUntil = null;
        }
    }

    public void recordFailure(LocalDateTime now, int lockAfter, LocalDateTime lockUntilIfReached) {
        failCount++;
        lastFailedAt = now;
        if (failCount >= lockAfter) {
            lockedUntil = lockUntilIfReached;
        }
    }

    public String getEmail() {
        return email;
    }

    public int getFailCount() {
        return failCount;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }
}
