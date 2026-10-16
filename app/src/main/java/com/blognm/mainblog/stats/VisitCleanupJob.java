package com.blognm.mainblog.stats;

import java.time.Clock;
import java.time.LocalDate;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 04:20. 보관 기간(blog.stats.retention-days, 기본 400일)이 지난 방문 기록을 지운다 (D-112).
 * 글 조회 기록(post_views)은 "같은 사람·같은 날 한 번"을 가리는 데만 쓰므로 어제보다 오래된 것을 지운다 (D-77).
 * 새벽 정리 작업을 켠 서버(app-a)에서만 돈다 (APP_JOBS_ENABLED, D-111).
 */
@Component
public class VisitCleanupJob {

    /** 한 번에 지우는 줄 수. 표를 오래 잠그지 않도록 나눠 지운다. */
    private static final int BATCH = 5000;

    private final JdbcTemplate jdbc;
    private final StatsProperties props;
    private final Clock clock;

    public VisitCleanupJob(JdbcTemplate jdbc, StatsProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "visitCleanup")
    public void cleanup() {
        purge();
        purgePostViews();
    }

    /** @return 지운 줄 수 */
    public int purge() {
        var before = LocalDate.now(clock).minusDays(props.retentionDays()).atStartOfDay();
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("DELETE FROM blog_visits WHERE visited_at < ? LIMIT " + BATCH, before);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }

    /** 어제보다 오래된 글 조회 기록. 오늘 것만 쓰고, 시간대 경계를 생각해 어제 것은 남긴다. @return 지운 줄 수 */
    public int purgePostViews() {
        LocalDate before = LocalDate.now(clock).minusDays(1);
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("DELETE FROM post_views WHERE view_date < ? LIMIT " + BATCH, before);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
