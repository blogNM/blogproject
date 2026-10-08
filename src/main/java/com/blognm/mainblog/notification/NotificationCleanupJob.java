package com.blognm.mainblog.notification;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일 04:00. 보관 기간(30일 또는 7일)이 지난 알림을 지운다.
 * 블로그 탭 알림은 그 블로그의 보관 설정(블로그장)을, 나머지는 받는 사람의 설정을 따른다 (003 Assumptions).
 */
@Component
public class NotificationCleanupJob {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public NotificationCleanupJob(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "notificationCleanup")
    @Transactional
    public void cleanup() {
        Map<String, Object> now = Map.of("now", LocalDateTime.now(clock));
        jdbc.update("""
                DELETE n FROM notifications n JOIN blogs b ON b.id = n.blog_id
                WHERE n.tab = 'BLOG' AND n.created_at < :now - INTERVAL b.notification_retention_days DAY
                """, now);
        jdbc.update("""
                DELETE n FROM notifications n JOIN members m ON m.id = n.recipient_id
                WHERE (n.tab <> 'BLOG' OR n.blog_id IS NULL)
                  AND n.created_at < :now - INTERVAL m.notification_retention_days DAY
                """, now);
    }
}
