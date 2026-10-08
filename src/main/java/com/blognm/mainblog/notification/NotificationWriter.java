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

    public enum Type { JOIN_REQUESTED, JOIN_APPROVED, JOIN_REJECTED, POST_DELETED, COMMENT, REPLY, LIKE }

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

    /** 댓글 탭 알림. 내 글에 댓글(COMMENT), 내 댓글에 답글(REPLY). 대상은 댓글이다 (SOC-04). */
    public void comment(long recipientId, Type type, long blogId, long actorId, long commentId) {
        jdbc.update("""
                INSERT INTO notifications (recipient_id, type, tab, blog_id, actor_id, target_type, target_id, created_at)
                VALUES (?, ?, 'COMMENT', ?, ?, 'COMMENT', ?, ?)
                """, recipientId, type.name(), blogId, actorId, commentId, LocalDateTime.now(clock));
    }

    /**
     * 좋아요 탭 알림. 취소했다 다시 눌러도 같은 사람·같은 글은 하루 한 번만 보낸다 (추천안 #11).
     */
    public void like(long recipientId, long blogId, long actorId, long postId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Integer already = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE recipient_id = ? AND type = 'LIKE' AND actor_id = ? AND target_id = ? AND created_at >= ?
                """, Integer.class, recipientId, actorId, postId, now.toLocalDate().atStartOfDay());
        if (already != null && already > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO notifications (recipient_id, type, tab, blog_id, actor_id, target_type, target_id, created_at)
                VALUES (?, 'LIKE', 'LIKE', ?, ?, 'POST', ?, ?)
                """, recipientId, blogId, actorId, postId, now);
    }
}
