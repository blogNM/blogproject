package com.blognm.mainblog.admin;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 관리자 활동 기록 (ADM-06, SEC-09). 조치와 개인정보 "전체 보기"를 누가 언제 했는지 남기고 1년 보관한다 (D-60). */
@Component
public class AdminLog {

    public enum Action { WARN, KICK, DELETE, HIDE, UNHIDE, CLOSE, RESTORE, VIEW_PII, REPORT, CREATE_ADMIN }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void write(long adminId, Action action, String targetType, long targetId, String reason) {
        String r = reason == null || reason.isBlank() ? null : reason.strip();
        if (r != null && r.length() > 500) {
            r = r.substring(0, 500);
        }
        jdbc.update("""
                INSERT INTO admin_action_logs (admin_id, action, target_type, target_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, adminId, action.name(), targetType, targetId, r, LocalDateTime.now(clock));
    }
}
