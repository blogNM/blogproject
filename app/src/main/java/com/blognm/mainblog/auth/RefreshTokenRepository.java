package com.blognm.mainblog.auth;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RefreshToken t where t.tokenHash = :tokenHash")
    int deleteByTokenHash(String tokenHash);

    /** 비밀번호를 바꾸거나 탈퇴하면 모든 기기의 로그인을 푼다 (SEC-04). */
    @Modifying
    @Query("delete from RefreshToken t where t.memberId = :memberId")
    int deleteAllByMemberId(long memberId);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt <= :now")
    int deleteExpired(LocalDateTime now);
}
