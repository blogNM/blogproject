package com.blognm.mainblog.notice;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.admin.AdminLog;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.post.MarkdownRenderer;

/**
 * 메인 공지사항 (BRD-10, 004 US7-3). 메인 관리자가 쓰고 고치고 지운다. 새 공지는 전체 회원에게 알린다
 * (공지 알림을 끈 회원은 빼고, SOC-04). 본문은 글과 같이 서버가 마크다운을 HTML로 바꾸고 걸러서 보낸다.
 */
@Service
public class NoticeService {

    static final int TITLE_MAX = 100;
    static final int CONTENT_MAX = 5000;
    static final int PAGE_SIZE = 20;

    /** 공지 카테고리 (D-98). 화면에는 일반·업데이트·수정·점검으로 보인다. */
    public enum Category { NOTICE, UPDATE, FIX, MAINTENANCE }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final NamedParameterJdbcTemplate jdbc;
    private final MarkdownRenderer renderer;
    private final AdminLog adminLog;
    private final Clock clock;

    public NoticeService(NamedParameterJdbcTemplate jdbc, MarkdownRenderer renderer, AdminLog adminLog, Clock clock) {
        this.jdbc = jdbc;
        this.renderer = renderer;
        this.adminLog = adminLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page list(int page) {
        return list(page, null);
    }

    /** category가 있으면 그 카테고리만 (공지사항 화면의 탭, D-98). */
    @Transactional(readOnly = true)
    public Page list(int page, Category category) {
        String where = "status = 'NORMAL'" + (category == null ? "" : " AND category = :category");
        MapSqlParameterSource params = new MapSqlParameterSource("category", category == null ? null : category.name());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notices WHERE " + where, params, Long.class);
        long count = total == null ? 0 : total;
        int current = Math.max(page, 1);
        List<Item> items = jdbc.query("""
                SELECT id, title, category, created_at FROM notices WHERE %s
                ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset
                """.formatted(where), params.addValue("size", PAGE_SIZE).addValue("offset", (current - 1) * PAGE_SIZE),
                (rs, i) -> new Item(rs.getLong("id"), rs.getString("title"), rs.getString("category"),
                        rs.getObject("created_at", LocalDateTime.class).format(DATE)));
        return new Page(items, current, (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE), count);
    }

    @Transactional(readOnly = true)
    public Detail get(long id) {
        return jdbc.query("""
                SELECT n.id, n.title, n.category, n.content_md, n.created_at, n.updated_at, a.name
                FROM notices n JOIN admins a ON a.id = n.admin_id WHERE n.id = :id AND n.status = 'NORMAL'
                """, Map.of("id", id), (rs, i) -> {
                    LocalDateTime created = rs.getObject("created_at", LocalDateTime.class);
                    LocalDateTime updated = rs.getObject("updated_at", LocalDateTime.class);
                    String md = rs.getString("content_md");
                    return new Detail(rs.getLong("id"), rs.getString("title"), rs.getString("category"), md,
                            renderer.toSafeHtml(md),
                            created.format(DATE), updated.isAfter(created.plusMinutes(1)) ? updated.format(DATE) : null);
                }).stream().findFirst().orElseThrow(NoticeService::notFound);
    }

    @Transactional
    public long create(long adminId, String rawTitle, String rawContent, Category category) {
        String title = title(rawTitle);
        String content = content(rawContent);
        LocalDateTime now = LocalDateTime.now(clock);
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO notices (admin_id, title, category, content_md, created_at, updated_at)
                VALUES (:admin, :title, :category, :content, :now, :now)
                """, new MapSqlParameterSource("admin", adminId).addValue("title", title).addValue("content", content)
                .addValue("category", category(category))
                .addValue("now", now), keys, new String[] {"id"});
        long id = keys.getKey().longValue();
        // 전체 회원에게 한 번에 넣는다. 공지 알림을 끈 회원은 뺀다 (SOC-04)
        jdbc.update("""
                INSERT INTO notifications (recipient_id, type, tab, blog_id, actor_id, target_type, target_id, detail, created_at)
                SELECT m.id, 'NOTICE', 'ADMIN', NULL, NULL, 'NOTICE', :id, :detail, :now FROM members m
                WHERE m.status = 'ACTIVE' AND NOT EXISTS (
                  SELECT 1 FROM notification_settings s WHERE s.member_id = m.id AND s.type = 'NOTICE' AND s.enabled = FALSE)
                """, new MapSqlParameterSource("id", id).addValue("now", now)
                .addValue("detail", title.length() > 100 ? title.substring(0, 100) : title));
        adminLog.write(adminId, AdminLog.Action.NOTICE, "NOTICE", id, "작성: " + title);
        return id;
    }

    /**
     * 깃허브에서 버전(릴리스)을 만들면 같은 내용을 공지로 올린다 (D-97). 작성자는 가장 먼저 만든 관리자.
     * 같은 제목의 공지가 이미 있으면 새로 쓰지 않고 본문만 바꾼 뒤 그 번호를 돌려준다.
     * 워크플로를 다시 돌려도 알림이 두 번 가지 않고, 노트에 더한 내용은 공지에도 반영된다.
     */
    @Transactional
    public long publishRelease(String rawTitle, String rawContent) {
        String title = title(rawTitle);
        List<Long> existing = jdbc.queryForList("SELECT id FROM notices WHERE title = :title AND status = 'NORMAL'",
                Map.of("title", title), Long.class);
        if (!existing.isEmpty()) {
            long id = existing.get(0);
            jdbc.update("UPDATE notices SET content_md = :content, updated_at = :now WHERE id = :id",
                    new MapSqlParameterSource("content", content(rawContent))
                            .addValue("now", LocalDateTime.now(clock)).addValue("id", id));
            return id;
        }
        Long adminId = jdbc.queryForObject("SELECT MIN(id) FROM admins", Map.of(), Long.class);
        if (adminId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_ADMIN", "관리자 계정이 없어 공지를 쓸 수 없습니다.");
        }
        return create(adminId, title, rawContent, Category.UPDATE);
    }

    @Transactional
    public void update(long adminId, long id, String rawTitle, String rawContent, Category category) {
        String title = title(rawTitle);
        int n = jdbc.update("""
                UPDATE notices SET title = :title, category = :category, content_md = :content, updated_at = :now
                WHERE id = :id AND status = 'NORMAL'
                """, new MapSqlParameterSource("title", title).addValue("content", content(rawContent))
                .addValue("category", category(category))
                .addValue("now", LocalDateTime.now(clock)).addValue("id", id));
        if (n == 0) {
            throw notFound();
        }
        adminLog.write(adminId, AdminLog.Action.NOTICE, "NOTICE", id, "수정: " + title);
    }

    @Transactional
    public void delete(long adminId, long id) {
        int n = jdbc.update("UPDATE notices SET status = 'DELETED' WHERE id = :id AND status = 'NORMAL'", Map.of("id", id));
        if (n == 0) {
            throw notFound();
        }
        adminLog.write(adminId, AdminLog.Action.DELETE, "NOTICE", id, null);
    }

    private static String title(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (t.isEmpty() || t.length() > TITLE_MAX) {
            throw ApiException.badRequest("INVALID_TITLE", "제목을 1~%d자로 써 주세요.".formatted(TITLE_MAX));
        }
        return t;
    }

    private static String content(String raw) {
        String c = raw == null ? "" : raw.strip();
        if (c.isEmpty() || c.length() > CONTENT_MAX) {
            throw ApiException.badRequest("INVALID_CONTENT", "본문을 1~%d자로 써 주세요.".formatted(CONTENT_MAX));
        }
        return c;
    }

    private static String category(Category category) {
        return (category == null ? Category.NOTICE : category).name();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "공지를 찾을 수 없습니다.");
    }

    public record Item(long id, String title, String category, String createdAt) {
    }

    public record Page(List<Item> items, int page, int totalPages, long total) {
    }

    public record Detail(long id, String title, String category, String content, String html, String createdAt,
            String updatedAt) {
    }
}
