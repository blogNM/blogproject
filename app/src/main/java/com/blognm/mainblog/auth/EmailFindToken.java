package com.blognm.mainblog.auth;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 이메일 찾기 결과에서 비밀번호 재설정으로 넘어갈 때 회원 번호 대신 쓰는 10분짜리 토큰 (D-26). */
@Entity
@Table(name = "email_find_tokens")
public class EmailFindToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    protected EmailFindToken() {
    }

    public static EmailFindToken issue(long memberId, String tokenHash, LocalDateTime expiresAt) {
        EmailFindToken t = new EmailFindToken();
        t.memberId = memberId;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        return t;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public Long getMemberId() {
        return memberId;
    }
}
