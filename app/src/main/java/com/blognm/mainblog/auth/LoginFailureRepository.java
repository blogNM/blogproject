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

    /** IP별 실패 수. 마지막 실패가 since보다 오래됐으면 없는 것으로 본다 (D-115). */
    @Query(value = "SELECT fail_count FROM login_failures WHERE email = :key AND last_failed_at >= :since",
            nativeQuery = true)
    Optional<Integer> recentFailCount(String key, LocalDateTime since);

    /**
     * IP별 실패를 한 문장으로 하나 더한다. 같은 IP에서 여러 계정으로 동시에 틀려도 행 잠금을 올리다 엇갈리지 않는다.
     * since보다 오래된 실패는 버리고 1부터 다시 센다. fail_count는 TINYINT라 100에서 멈춘다 (D-115).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO login_failures (email, fail_count, last_failed_at) VALUES (:key, 1, :now)
            ON DUPLICATE KEY UPDATE fail_count = IF(last_failed_at < :since, 1, LEAST(fail_count + 1, 100)),
                                    last_failed_at = :now
            """, nativeQuery = true)
    void addRecentFailure(String key, LocalDateTime now, LocalDateTime since);

    @Modifying
    @Query("delete from LoginFailure f where f.lastFailedAt < :before and (f.lockedUntil is null or f.lockedUntil < :now)")
    int deleteStale(LocalDateTime before, LocalDateTime now);
}
