package com.blognm.mainblog.auth;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findFirstByEmailAndPurposeOrderByIdDesc(String email,
            EmailVerification.Purpose purpose);

    @Modifying
    @Query("delete from EmailVerification v where v.email = :email and v.purpose = :purpose")
    void deleteAllFor(String email, EmailVerification.Purpose purpose);

    @Modifying
    @Query("delete from EmailVerification v where v.expiresAt <= :now")
    int deleteExpired(LocalDateTime now);
}
