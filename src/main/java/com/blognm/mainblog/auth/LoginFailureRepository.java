package com.blognm.mainblog.auth;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LoginFailureRepository extends JpaRepository<LoginFailure, String> {

    /** 같은 이메일로 동시에 로그인해도 실패 횟수를 빠뜨리지 않게 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from LoginFailure f where f.email = :email")
    Optional<LoginFailure> findForUpdate(String email);

    /** 처음 실패한 이메일이면 행을 만든다. 동시에 들어와도 한 행만 생긴다. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO login_failures (email, fail_count, last_failed_at) VALUES (:email, 0, :now)",
            nativeQuery = true)
    void insertIfAbsent(String email, LocalDateTime now);

    @Modifying
    @Query("delete from LoginFailure f where f.lastFailedAt < :before and (f.lockedUntil is null or f.lockedUntil < :now)")
    int deleteStale(LocalDateTime before, LocalDateTime now);
}
