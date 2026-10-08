package com.blognm.mainblog.notification;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 알림 저장. 알림을 보여주는 화면(30초 폴링, SOC-04)은 5단계(소셜·알림)에서 만든다.
 * 그 전에도 알림이 빠지지 않도록 지금부터 기록해 둔다.
 */
@Component
public class NotificationWriter {

    public enum Type { JOIN_REQUESTED, JOIN_APPROVED, JOIN_REJECTED, POST_DELETED }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 블로그 탭 알림. 대상은 그 블로그다. */
    public void blog(long recipientId, Type type, long blogId, Long actorId) {
        jdbc.update("""
                INSERT INTO notifications (recipient_id, type, tab, blog_id, actor_id, target_type, target_id, created_at)
                VALUES (?, ?, 'BLOG', ?, ?, 'BLOG', ?, ?)
                """, recipientId, type.name(), blogId, actorId, blogId, LocalDateTime.now(clock));
    }

    /** 블로그 탭 알림. 대상은 글이다. 블로그장이 내 글을 지웠을 때 등 (D-58). */
    public void post(long recipientId, Type type, long blogId, Long actorId, long postId) {
        jdbc.update("""
                INSERT INTO notifications (recipient_id, type, tab, blog_id, actor_id, target_type, target_id, created_at)
                VALUES (?, ?, 'BLOG', ?, ?, 'POST', ?, ?)
                """, recipientId, type.name(), blogId, actorId, postId, LocalDateTime.now(clock));
    }
}
