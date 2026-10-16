package com.blognm.mainblog.notification;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 04:00. 보관 기간(30일 또는 7일)이 지난 알림을 지운다.
 * 블로그 탭 알림은 그 블로그의 보관 설정(블로그장)을, 나머지는 받는 사람의 설정을 따른다 (003 Assumptions).
 * 공지 하나에 회원 수만큼 알림이 생기므로, 표를 오래 잠그지 않게 나눠 지운다 (VisitCleanupJob과 같은 방식).
 */
@Component
public class NotificationCleanupJob {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public NotificationCleanupJob(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 한 번에 지우는 줄 수 */
    private static final int BATCH = 5000;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "notificationCleanup")
    public void cleanup() {
        purge();
    }

    /** @return 지운 알림 수 */
    public int purge() {
        Map<String, Object> now = Map.of("now", LocalDateTime.now(clock));
        return deleteInBatches("""
                SELECT n.id FROM notifications n JOIN blogs b ON b.id = n.blog_id
                WHERE n.tab = 'BLOG' AND n.created_at < :now - INTERVAL b.notification_retention_days DAY
                LIMIT %d
                """.formatted(BATCH), now)
                + deleteInBatches("""
                        SELECT n.id FROM notifications n JOIN members m ON m.id = n.recipient_id
                        WHERE (n.tab <> 'BLOG' OR n.blog_id IS NULL)
                          AND n.created_at < :now - INTERVAL m.notification_retention_days DAY
                        LIMIT %d
                        """.formatted(BATCH), now);
    }

    private int deleteInBatches(String selectIds, Map<String, Object> params) {
        int total = 0;
        List<Long> ids;
        do {
            ids = jdbc.queryForList(selectIds, params, Long.class);
            if (!ids.isEmpty()) {
                total += jdbc.update("DELETE FROM notifications WHERE id IN (:ids)", Map.of("ids", ids));
            }
        } while (ids.size() == BATCH);
        return total;
    }
}
