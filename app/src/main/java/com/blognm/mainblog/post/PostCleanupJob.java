package com.blognm.mainblog.post;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.blognm.mainblog.image.FileStorage;

/**
 * 매일 04:00. 지운 지 30일 지난 글을 완전히 지우고(US2-4), 하루 넘게 글에 붙지 않은 사진을 지운다.
 * DB를 먼저 지우고 커밋한 뒤 파일을 지운다. 파일 삭제가 실패해도 DB와 어긋나 보이는 일은 없다.
 */
@Component
public class PostCleanupJob {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FileStorage storage;
    private final Clock clock;

    public PostCleanupJob(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, FileStorage storage, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "postCleanup")
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> files = tx.execute(status -> {
            List<Long> postIds = jdbc.queryForList(
                    "SELECT id FROM posts WHERE status = 'DELETED' AND deleted_at < :before",
                    Map.of("before", now.minusDays(30)), Long.class);
            List<String> names = new java.util.ArrayList<>();
            if (!postIds.isEmpty()) {
                Map<String, Object> ids = Map.of("ids", postIds);
                names.addAll(jdbc.queryForList("SELECT stored_name FROM images WHERE post_id IN (:ids)", ids,
                        String.class));
                jdbc.update("DELETE FROM images WHERE post_id IN (:ids)", ids);
                jdbc.update("DELETE FROM post_tags WHERE post_id IN (:ids)", ids);
                jdbc.update("DELETE FROM post_likes WHERE post_id IN (:ids)", ids);
                jdbc.update("DELETE FROM post_views WHERE post_id IN (:ids)", ids);
                jdbc.update("DELETE FROM comments WHERE post_id IN (:ids) AND parent_id IS NOT NULL", ids);
                jdbc.update("DELETE FROM comments WHERE post_id IN (:ids)", ids);
                jdbc.update("DELETE FROM posts WHERE id IN (:ids)", ids);
            }
            // 글에 붙지 않은 사진. 프로필·블로그 대표 사진, 링크 미리보기 썸네일(D-92), 관리자 공지 사진(D-102)은 남긴다.
            Map<String, Object> orphan = Map.of("before", now.minusDays(1));
            String where = """
                    post_id IS NULL AND admin_uploader_id IS NULL AND created_at < :before
                      AND id NOT IN (SELECT profile_image_id FROM members WHERE profile_image_id IS NOT NULL)
                      AND id NOT IN (SELECT cover_image_id FROM blogs WHERE cover_image_id IS NOT NULL)
                      AND id NOT IN (SELECT image_id FROM link_previews WHERE image_id IS NOT NULL)
                    """;
            names.addAll(jdbc.queryForList("SELECT stored_name FROM images WHERE " + where, orphan, String.class));
            jdbc.update("DELETE FROM images WHERE " + where, orphan);
            return names;
        });
        files.forEach(storage::delete);
    }
}
