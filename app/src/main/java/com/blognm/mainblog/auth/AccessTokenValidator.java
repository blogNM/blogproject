package com.blognm.mainblog.auth;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 서명이 맞는 Access Token이라도 회원이 탈퇴했거나, 토큰 발급 뒤 비밀번호를 바꿨으면 받지 않는다 (T031, SEC-04).
 * 토큰 내용만 믿지 않고 매 요청 DB로 확인한다 (헌법 II).
 */
@Component
public class AccessTokenValidator {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccessTokenValidator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean isValid(JwtProvider.MemberToken token) {
        List<Boolean> rows = jdbc.query(
                "SELECT status, credentials_changed_at FROM members WHERE id = ?",
                (rs, i) -> {
                    if (!"ACTIVE".equals(rs.getString("status"))) {
                        return false;
                    }
                    LocalDateTime changedAt = rs.getObject("credentials_changed_at", LocalDateTime.class);
                    return changedAt == null
                            || !token.issuedAt().isBefore(changedAt.atZone(clock.getZone()).toInstant());
                },
                token.memberId());
        return !rows.isEmpty() && rows.get(0);
    }
}
