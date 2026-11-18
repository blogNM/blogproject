package com.blognm.mainblog.auth;

import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.blognm.mainblog.common.ApiException;

/**
 * 이메일 한 곳에 하루 보내는 인증번호 수 (D-130). 서버 두 대가 같이 세도록 DB에서 센다 (SCL-01).
 * 한 줄을 늘리고 바로 읽으므로, 그 줄은 트랜잭션이 끝날 때까지 잠겨 동시에 보내도 빠뜨리지 않는다.
 * 넘으면 예외를 던지고, 부르는 쪽 트랜잭션이 되돌려져 센 숫자도 늘지 않는다.
 */
@Component
public class EmailDailyQuota {

    private final JdbcTemplate jdbc;

    public EmailDailyQuota(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 반드시 트랜잭션 안에서 부른다. limit을 넘으면 MAIL_DAILY_LIMIT(429). */
    public void acquire(String email, EmailVerification.Purpose purpose, LocalDate day, int limit) {
        jdbc.update("""
                INSERT INTO email_daily_sends (email, purpose, send_date, sent_count) VALUES (?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE sent_count = sent_count + 1""", email, purpose.name(), day);
        Integer count = jdbc.queryForObject(
                "SELECT sent_count FROM email_daily_sends WHERE email = ? AND purpose = ? AND send_date = ?",
                Integer.class, email, purpose.name(), day);
        if (count != null && count > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "MAIL_DAILY_LIMIT",
                    "이 이메일로는 오늘 인증번호를 더 보낼 수 없습니다. 내일 다시 시도해 주세요.");
        }
    }

    /** 어제보다 오래된 줄을 지운다 (새벽 정리 작업). */
    public int deleteBefore(LocalDate day) {
        return jdbc.update("DELETE FROM email_daily_sends WHERE send_date < ?", day);
    }
}
