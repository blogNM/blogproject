package com.blognm.mainblog.auth;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findFirstByEmailAndPurposeOrderByIdDesc(String email,
            EmailVerification.Purpose purpose);

    /**
     * 인증번호를 맞혀 볼 때 쓴다. 동시에 여러 번 보내도 틀린 횟수를 빠뜨리지 않게 행을 잠근다.
     * 잠그지 않으면 수백 개를 한꺼번에 보낼 때 모두 "0번 틀림"으로 읽혀 5번 제한을 넘을 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findTopByEmailAndPurposeOrderByIdDesc(String email,
            EmailVerification.Purpose purpose);

    @Modifying
    @Query("delete from EmailVerification v where v.email = :email and v.purpose = :purpose")
    void deleteAllFor(String email, EmailVerification.Purpose purpose);

    @Modifying
    @Query("delete from EmailVerification v where v.expiresAt <= :now")
    int deleteExpired(LocalDateTime now);
}
