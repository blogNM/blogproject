package com.blognm.mainblog.auth;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 이메일 인증번호 (USR-02). 회원과 연결하지 않는다 (D-28). */
@Entity
@Table(name = "email_verifications")
public class EmailVerification {

    public enum Purpose { SIGNUP, PASSWORD_RESET }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    @Enumerated(EnumType.STRING)
    private Purpose purpose;

    @Column(name = "code_hash")
    private String codeHash;

    @Column(name = "fail_count")
    @JdbcTypeCode(SqlTypes.TINYINT)
    private int failCount;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    protected EmailVerification() {
    }

    public static EmailVerification issue(String email, Purpose purpose, String codeHash, LocalDateTime now,
            LocalDateTime expiresAt) {
        EmailVerification v = new EmailVerification();
        v.email = email;
        v.purpose = purpose;
        v.codeHash = codeHash;
        v.sentAt = now;
        v.expiresAt = expiresAt;
        return v;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public int recordFailure() {
        return ++failCount;
    }

    public void markVerified(LocalDateTime now, LocalDateTime newExpiresAt) {
        verifiedAt = now;
        expiresAt = newExpiresAt;
    }

    public String getEmail() {
        return email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public int getFailCount() {
        return failCount;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
