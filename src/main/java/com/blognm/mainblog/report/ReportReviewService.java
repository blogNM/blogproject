package com.blognm.mainblog.report;

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

import com.blognm.mainblog.blog.BlogLifecycleService;
import com.blognm.mainblog.blog.SanctionService;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.notification.NotificationWriter;

/**
 * 신고 처리 (BLG-13, ADM-04, D-38, D-45, D-85). 블로그 안의 회원·글·댓글은 블로그장(멤버 관리 권한이 있는 부블로그장 포함)이,
 * 블로그·블로그장 관련과 메인 프로필 신고는 메인 관리자가 처리한다. 처리 결과는 신고자에게 알림으로 간다.
 */
@Service
public class ReportReviewService {

    /** 블로그장이 고르는 결과. */
    public enum OwnerResult { NONE, WARNING, SUSPEND, KICK }

    /** 메인 관리자가 고르는 결과. 회원 정지는 없다 (D-44). */
    public enum AdminResult { NONE, OWNER_WARNING, OWNER_KICK, BLOG_CLOSE }

    static final Map<String, String> RESULT_LABEL = Map.of("NONE", "문제 없음", "WARNING", "경고", "SUSPEND", "정지",
            "KICK", "강제 퇴장", "OWNER_WARNING", "블로그장 경고", "OWNER_KICK", "블로그장 권한 박탈", "BLOG_CLOSE", "블로그 폐쇄");
    static final Map<String, String> REASON_LABEL = Map.of("SPAM", "스팸·광고", "ABUSE", "욕설·괴롭힘", "OBSCENE",
            "음란·불쾌한 내용", "PRIVACY", "개인정보 노출", "COPYRIGHT", "저작권 침해", "ETC", "기타");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final int PAGE_SIZE = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final SanctionService sanctions;
    private final BlogLifecycleService lifecycle;
    private final NotificationWriter notifications;
    private final Clock clock;

    public ReportReviewService(NamedParameterJdbcTemplate jdbc, SanctionService sanctions,
            BlogLifecycleService lifecycle, NotificationWriter notifications, Clock clock) {
        this.jdbc = jdbc;
        this.sanctions = sanctions;
        this.lifecycle = lifecycle;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ----- 목록 -----

    /** 블로그장 화면: 그 블로그로 접수된 신고만. 관리자에게 간 신고(블로그장 관련)는 보이지 않는다 (US2-2). */
    @Transactional(readOnly = true)
    public Page forBlog(String slug, boolean pending, int page) {
        MapSqlParameterSource p = new MapSqlParameterSource("slug", slug);
        return list(" r.handler = 'BLOG_OWNER' AND r.blog_id = (SELECT id FROM blogs WHERE slug = :slug)", pending, page, p);
    }

    /** 메인 관리자 화면: 관리자에게 접수된 신고만. 블로그 안의 일반 멤버 신고는 보이지 않는다. */
    @Transactional(readOnly = true)
    public Page forAdmin(boolean pending, int page) {
        return list(" r.handler = 'ADMIN'", pending, page, new MapSqlParameterSource());
    }

    private Page list(String where, boolean pending, int page, MapSqlParameterSource p) {
        String w = " WHERE" + where + " AND r.status = " + (pending ? "'PENDING'" : "'DONE'");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM reports r" + w, p, Long.class);
        int current = Math.max(page, 1);
        p.addValue("size", PAGE_SIZE).addValue("offset", (current - 1) * PAGE_SIZE);
        List<Item> items = jdbc.query("""
                SELECT r.id, r.target_type, r.target_id, r.reason_code, r.detail, r.status, r.result, r.created_at,
                       r.processed_at, b.slug, b.name AS blog_name, b.owner_id,
                       CASE r.target_type
                         WHEN 'MEMBER' THEN r.target_id
                         WHEN 'POST' THEN (SELECT author_id FROM posts WHERE id = r.target_id)
                         WHEN 'COMMENT' THEN (SELECT author_id FROM comments WHERE id = r.target_id)
                         WHEN 'BLOG' THEN b.owner_id
                       END AS author_id,
                       CASE r.target_type
                         WHEN 'POST' THEN (SELECT title FROM posts WHERE id = r.target_id)
                         WHEN 'COMMENT' THEN (SELECT LEFT(content, 60) FROM comments WHERE id = r.target_id)
                         WHEN 'BLOG' THEN b.name
                       END AS summary,
                       CASE r.target_type
                         WHEN 'POST' THEN (SELECT status FROM posts WHERE id = r.target_id)
                         WHEN 'COMMENT' THEN (SELECT status FROM comments WHERE id = r.target_id)
                       END AS target_status,
                       CASE r.target_type WHEN 'COMMENT' THEN (SELECT post_id FROM comments WHERE id = r.target_id) END AS post_id,
                       (SELECT COUNT(*) FROM reports x WHERE x.target_type = r.target_type AND x.target_id = r.target_id
                          AND x.status = 'PENDING') AS same_target
                FROM reports r LEFT JOIN blogs b ON b.id = r.blog_id
                """ + w + " ORDER BY r.created_at " + (pending ? "ASC" : "DESC") + ", r.id LIMIT :size OFFSET :offset", p,
                (rs, i) -> {
                    Long authorId = rs.getObject("author_id", Long.class);
                    String type = rs.getString("target_type");
                    String slug = rs.getString("slug");
                    Long postId = rs.getObject("post_id", Long.class);
                    String link = switch (type) {
                        case "POST" -> slug == null ? null : "/blog/" + slug + "/posts/" + rs.getLong("target_id");
                        case "COMMENT" -> slug == null || postId == null ? null
                                : "/blog/" + slug + "/posts/" + postId + "#comment-" + rs.getLong("target_id");
                        case "BLOG" -> slug == null ? null : "/blog/" + slug;
                        default -> "/members/" + rs.getLong("target_id");
                    };
                    String targetStatus = rs.getString("target_status");
                    LocalDateTime processed = rs.getObject("processed_at", LocalDateTime.class);
                    return new Item(rs.getLong("id"), type, rs.getLong("target_id"), rs.getString("summary"),
                            targetStatus != null && !"NORMAL".equals(targetStatus), link, authorId,
                            authorId == null ? null : nickname(authorId), authorId != null && authorId.equals(
                                    rs.getObject("owner_id", Long.class)),
                            rs.getString("reason_code"), REASON_LABEL.get(rs.getString("reason_code")),
                            rs.getString("detail"), rs.getObject("created_at", LocalDateTime.class).format(DATE_TIME),
                            rs.getString("status"), rs.getString("result"),
                            rs.getString("result") == null ? null : RESULT_LABEL.get(rs.getString("result")),
                            processed == null ? null : processed.format(DATE_TIME), slug, rs.getString("blog_name"),
                            rs.getInt("same_target"));
                });
        long count = total == null ? 0 : total;
        return new Page(items, current, (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE), count);
    }

    private String nickname(long memberId) {
        return jdbc.queryForList("SELECT CASE WHEN status = 'ACTIVE' THEN nickname ELSE '탈퇴한 회원' END FROM members WHERE id = :id",
                Map.of("id", memberId), String.class).stream().findFirst().orElse(null);
    }

    // ----- 처리 -----

    /** 블로그장이 처리한다. 경고·정지·강제 퇴장은 신고된 사람(글·댓글이면 그 작성자)에게 한다. */
    @Transactional
    public void processByOwner(String slug, long reportId, long actorId, OwnerResult result,
            SanctionService.Duration duration, String reason) {
        Report r = lock(reportId);
        Long blogId = jdbc.queryForObject("SELECT id FROM blogs WHERE slug = :slug", Map.of("slug", slug), Long.class);
        if (!"BLOG_OWNER".equals(r.handler()) || r.blogId() == null || !r.blogId().equals(blogId)) {
            throw notFound();
        }
        requirePending(r);
        if (result == null) {
            throw ApiException.badRequest("INVALID_RESULT", "처리 결과를 골라 주세요.");
        }
        if (result != OwnerResult.NONE) {
            long target = authorOf(r);
            sanctions.sanction(slug, actorId, target, SanctionService.Action.valueOf(result.name()), duration, reason,
                    reportId);
        }
        finish(r, result.name(), actorId, null);
    }

    /** 메인 관리자가 처리한다. 블로그장 경고·권한 박탈·블로그 폐쇄는 신고가 걸린 블로그에 한다. */
    @Transactional
    public String processByAdmin(long reportId, long adminId, AdminResult result, String reason) {
        Report r = lock(reportId);
        if (!"ADMIN".equals(r.handler())) {
            throw notFound();
        }
        requirePending(r);
        if (result == null) {
            throw ApiException.badRequest("INVALID_RESULT", "처리 결과를 골라 주세요.");
        }
        if (result != AdminResult.NONE && r.blogId() == null) {
            throw ApiException.badRequest("NO_BLOG", "블로그와 관계없는 신고는 '문제 없음'으로만 처리합니다. 글·댓글은 따로 숨기거나 지울 수 있습니다.");
        }
        switch (result) {
            case OWNER_WARNING -> lifecycle.warnOwner(r.blogId(), adminId, reason, reportId);
            case OWNER_KICK -> lifecycle.kickOwner(r.blogId());
            case BLOG_CLOSE -> lifecycle.closeByAdmin(r.blogId());
            case NONE -> {
            }
        }
        finish(r, result.name(), null, adminId);
        return result.name();
    }

    private void finish(Report r, String result, Long memberId, Long adminId) {
        jdbc.update("""
                UPDATE reports SET status = 'DONE', result = :result, processed_by_member_id = :member,
                       processed_by_admin_id = :admin, processed_at = :now WHERE id = :id
                """, new MapSqlParameterSource("result", result).addValue("member", memberId).addValue("admin", adminId)
                .addValue("now", LocalDateTime.now(clock)).addValue("id", r.id()));
        notifications.reportResult(r.reporterId(), r.blogId(), r.id(), RESULT_LABEL.get(result));
    }

    private long authorOf(Report r) {
        String sql = switch (r.targetType()) {
            case "MEMBER" -> "SELECT :id";
            case "POST" -> "SELECT author_id FROM posts WHERE id = :id";
            case "COMMENT" -> "SELECT author_id FROM comments WHERE id = :id";
            default -> throw ApiException.badRequest("INVALID_RESULT", "이 신고에는 고를 수 없는 결과입니다.");
        };
        return jdbc.queryForObject(sql, Map.of("id", r.targetId()), Long.class);
    }

    private Report lock(long id) {
        return jdbc.query("SELECT id, reporter_id, target_type, target_id, blog_id, handler, status FROM reports WHERE id = :id FOR UPDATE",
                Map.of("id", id), (rs, i) -> new Report(rs.getLong("id"), rs.getLong("reporter_id"),
                        rs.getString("target_type"), rs.getLong("target_id"), rs.getObject("blog_id", Long.class),
                        rs.getString("handler"), rs.getString("status"))).stream().findFirst()
                .orElseThrow(ReportReviewService::notFound);
    }

    private static void requirePending(Report r) {
        if (!"PENDING".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PROCESSED", "이미 처리한 신고입니다.");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "신고를 찾을 수 없습니다.");
    }

    private record Report(long id, long reporterId, String targetType, long targetId, Long blogId, String handler,
            String status) {
    }

    /** targetGone: 신고된 글·댓글이 이미 지워졌거나 숨겨짐. byOwner: 신고된 사람이 그 블로그의 블로그장. */
    public record Item(long id, String targetType, long targetId, String summary, boolean targetGone, String link,
            Long authorId, String authorNickname, boolean byOwner, String reason, String reasonLabel, String detail,
            String createdAt, String status, String result, String resultLabel, String processedAt, String blogSlug,
            String blogName, int sameTargetPending) {
    }

    public record Page(List<Item> items, int page, int totalPages, long total) {
    }
}
