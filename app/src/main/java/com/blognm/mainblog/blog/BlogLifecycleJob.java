package com.blognm.mainblog.blog;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.blognm.mainblog.image.FileStorage;

/**
 * 매일 04:00 (Asia/Seoul). ① 예정 시각이 지난 블로그 폐쇄 ② 폐쇄 3일·1일 전 알림 ③ 7일 무응답 위임 취소
 * ④ 닫힌 지 30일 지난 블로그 정리 ⑤ 1년 지난 운영 기록 정리 (BLG-09, D-12, D-31, D-35, D-60).
 * 서버가 꺼져 있던 날의 몫도 다음 배치가 처리하고, 보낸 알림은 기록해 두어 두 번 보내지 않는다.
 */
@Component
public class BlogLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(BlogLifecycleJob.class);

    private final BlogLifecycleService lifecycle;
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final Clock clock;

    public BlogLifecycleJob(BlogLifecycleService lifecycle, JdbcTemplate jdbc, FileStorage storage, Clock clock) {
        this.lifecycle = lifecycle;
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "blogLifecycle")
    public void run() {
        int closed = lifecycle.closeDue();
        int reminded = lifecycle.sendReminders();
        int expired = lifecycle.expireTransfers();
        List<String> files = lifecycle.purgeClosed();
        files.forEach(storage::delete);
        LocalDateTime yearAgo = LocalDateTime.now(clock).minusYears(1);
        jdbc.update("DELETE FROM admin_action_logs WHERE created_at < ?", yearAgo);
        jdbc.update("DELETE FROM member_sanctions WHERE created_at < ?", yearAgo);
        jdbc.update("DELETE FROM owner_warnings WHERE created_at < ?", yearAgo);
        log.info("블로그 배치: 폐쇄 {}개, 예고 알림 {}개 블로그, 위임 취소 {}건, 정리한 사진 {}개", closed, reminded, expired,
                files.size());
    }
}
