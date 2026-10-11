package com.blognm.mainblog.auth;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailFindTokenRepository extends JpaRepository<EmailFindToken, Long> {

    Optional<EmailFindToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from EmailFindToken t where t.memberId = :memberId")
    int deleteAllByMemberId(long memberId);

    @Modifying
    @Query("delete from EmailFindToken t where t.expiresAt <= :now")
    int deleteExpired(LocalDateTime now);
}
