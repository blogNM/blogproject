package com.blognm.mainblog.member;

import java.time.LocalDateTime;

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
}
