package com.blognm.mainblog.auth;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 회원에 연결한 바깥 로그인 계정 (D-103). 구글은 이메일이 아니라 바뀌지 않는 사용자 번호(sub)로 찾는다. */
@Entity
@Table(name = "social_accounts")
public class SocialAccount {

    public static final String GOOGLE = "google";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    private String provider;

    @Column(name = "provider_user_id")
    private String providerUserId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    protected SocialAccount() {
    }

    public static SocialAccount link(long memberId, String provider, String providerUserId, LocalDateTime now) {
        SocialAccount a = new SocialAccount();
        a.memberId = memberId;
        a.provider = provider;
        a.providerUserId = providerUserId;
        a.createdAt = now;
        return a;
    }

    public Long getMemberId() {
        return memberId;
    }
}
