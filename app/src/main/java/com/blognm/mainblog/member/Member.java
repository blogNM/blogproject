package com.blognm.mainblog.member;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "members")
public class Member {

    public enum Status { ACTIVE, WITHDRAWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    private String name;

    private String nickname;

    private String phone;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "terms_agreed_at")
    private LocalDateTime termsAgreedAt;

    @Column(name = "privacy_agreed_at")
    private LocalDateTime privacyAgreedAt;

    @Column(name = "age14_confirmed_at")
    private LocalDateTime age14ConfirmedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "credentials_changed_at")
    private LocalDateTime credentialsChangedAt;

    /** 테스트 회원 (D-113). 테스트 데이터 넣기로만 켜지고, 가입·정보 수정으로는 바뀌지 않는다. */
    @Column(name = "is_test", insertable = false, updatable = false)
    private boolean test;

    protected Member() {
    }

    public static Member join(String email, String passwordHash, String name, String nickname, String phone,
            LocalDateTime now) {
        Member m = new Member();
        m.email = email;
        m.passwordHash = passwordHash;
        m.name = name;
        m.nickname = nickname;
        m.phone = phone;
        m.status = Status.ACTIVE;
        m.termsAgreedAt = now;
        m.privacyAgreedAt = now;
        m.age14ConfirmedAt = now;
        m.createdAt = now;
        return m;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isTest() {
        return test;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changePhone(String phone) {
        this.phone = phone;
    }

    /** 이전에 발급된 Access Token은 다음 요청부터 받지 않는다 (T031). JWT 발급 시각이 초 단위라 초로 자른다. */
    public void changePassword(String passwordHash, LocalDateTime now) {
        this.passwordHash = passwordHash;
        this.credentialsChangedAt = now.truncatedTo(ChronoUnit.SECONDS);
    }

    /** 로그인용 이메일을 바로 비워 같은 이메일로 다시 가입할 수 있게 한다 (USR-05). 나머지 개인정보는 30일 뒤 지운다. */
    public void withdraw(LocalDateTime now) {
        this.email = null;
        this.status = Status.WITHDRAWN;
        this.withdrawnAt = now;
        this.credentialsChangedAt = now.truncatedTo(ChronoUnit.SECONDS);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public String getPhone() {
        return phone;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCredentialsChangedAt() {
        return credentialsChangedAt;
    }
}
