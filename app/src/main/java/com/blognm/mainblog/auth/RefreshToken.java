package com.blognm.mainblog.auth;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Refresh Token. 원문은 쿠키에만, DB에는 해시만 둔다 (SEC-04). */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "remember_me")
    private boolean rememberMe;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    protected RefreshToken() {
    }

    public static RefreshToken forMember(Long memberId, String tokenHash, boolean rememberMe, LocalDateTime now,
            LocalDateTime expiresAt) {
        RefreshToken t = new RefreshToken();
        t.memberId = memberId;
        t.tokenHash = tokenHash;
        t.rememberMe = rememberMe;
        t.lastActivityAt = now;
        t.expiresAt = expiresAt;
        t.createdAt = now;
        return t;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void touch(LocalDateTime now) {
        lastActivityAt = now;
    }

    public Long getMemberId() {
        return memberId;
    }

    public boolean isRememberMe() {
        return rememberMe;
    }

    public LocalDateTime getLastActivityAt() {
        return lastActivityAt;
    }
}
