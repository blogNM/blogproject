package com.blognm.mainblog.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.Masking;
import com.blognm.mainblog.notification.NotificationWriter;

/**
 * 메인 관리자 조회·조치 (ADM-01~06). 조치마다 활동 기록을 남긴다.
 * 관리자는 회원 정지·강제 퇴장을 하지 않는다. 그건 블로그장 몫이다 (ADM-01, D-17).
 */
@Service
public class AdminService {

    static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationWriter notifications;
    private final AdminLog adminLog;
    private final Clock clock;

    public AdminService(NamedParameterJdbcTemplate jdbc, NotificationWriter notifications, AdminLog adminLog, Clock clock) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.adminLog = adminLog;
        this.clock = clock;
    }

    // ----- 회원 (ADM-01) -----

    @Transactional(readOnly = true)
    public Page<MemberItem> members(String q, int page) {
        MapSqlParameterSource p = new MapSqlParameterSource("q", like(q));
        String where = q == null || q.isBlank() ? "" : " WHERE m.nickname LIKE :q OR m.email LIKE :q OR m.name LIKE :q";
        return page("SELECT COUNT(*) FROM members m" + where, """
                SELECT m.id, m.nickname, m.email, m.status, m.created_at,
                       (SELECT COUNT(*) FROM blog_memberships bm WHERE bm.member_id = m.id AND bm.status = 'ACTIVE') AS blogs,
                       (SELECT COUNT(*) FROM posts x WHERE x.author_id = m.id AND x.status = 'NORMAL') AS posts
                FROM members m""" + where + " ORDER BY m.id DESC", p, page,
                (rs, i) -> new MemberItem(rs.getLong("id"), shownNickname(rs.getString("nickname"), rs.getString("status")),
                        Masking.email(rs.getString("email")), rs.getString("status"), time(rs.getObject("created_at", LocalDateTime.class)),
                        rs.getInt("blogs"), rs.getInt("posts")));
    }

    /** 회원 상세. 이름·이메일·전화번호는 가려서 보여준다 (4.4). */
    @Transactional(readOnly = true)
    public MemberDetail member(long id) {
        Map<String, Object> m = memberRow(id);
        LocalDateTime yearAgo = LocalDateTime.now(clock).minusYears(1);
        List<MemberBlog> blogs = jdbc.query("""
                SELECT b.slug, b.name, bm.role, bm.status, bm.suspended_until,
                       (SELECT COUNT(*) FROM member_sanctions s WHERE s.blog_id = b.id AND s.member_id = :id
                          AND s.type = 'WARNING' AND s.created_at >= :yearAgo) AS warnings,
                       (SELECT COUNT(*) FROM member_sanctions s WHERE s.blog_id = b.id AND s.member_id = :id
                          AND s.type = 'SUSPEND' AND s.created_at >= :yearAgo) AS suspensions
                FROM blog_memberships bm JOIN blogs b ON b.id = bm.blog_id
                WHERE bm.member_id = :id ORDER BY bm.id
                """, new MapSqlParameterSource("id", id).addValue("yearAgo", yearAgo),
                (rs, i) -> new MemberBlog(rs.getString("slug"), rs.getString("name"), rs.getString("role"), rs.getString("status"),
                        time(rs.getObject("suspended_until", LocalDateTime.class)), rs.getInt("warnings"), rs.getInt("suspensions")));
        return new MemberDetail(id, shownNickname((String) m.get("nickname"), (String) m.get("status")),
                maskName((String) m.get("name")), Masking.email((String) m.get("email")), Masking.phone((String) m.get("phone")),
                (String) m.get("status"), time((LocalDateTime) m.get("created_at")), time((LocalDateTime) m.get("withdrawn_at")), blogs);
    }

    /** "전체 보기". 누가 언제 봤는지 남긴다 (ADM-06). */
    @Transactional
    public Pii revealMember(long adminId, long id) {
        Map<String, Object> m = memberRow(id);
        adminLog.write(adminId, AdminLog.Action.VIEW_PII, "MEMBER", id, null);
        return new Pii((String) m.get("name"), (String) m.get("email"), (String) m.get("phone"));
    }

    private Map<String, Object> memberRow(long id) {
        return jdbc.queryForList("SELECT nickname, name, email, phone, status, created_at, withdrawn_at FROM members WHERE id = :id",
                Map.of("id", id)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "회원을 찾을 수 없습니다."));
    }

    // ----- 블로그 (ADM-02) -----

    @Transactional(readOnly = true)
    public Page<BlogItem> blogs(String q, int page) {
        MapSqlParameterSource p = new MapSqlParameterSource("q", like(q)).addValue("yearAgo", LocalDateTime.now(clock).minusYears(1));
        String where = " WHERE b.status <> 'CLOSED'" + (q == null || q.isBlank() ? "" : " AND (b.slug LIKE :q OR b.name LIKE :q)");
        return page("SELECT COUNT(*) FROM blogs b" + where, """
                SELECT b.id, b.slug, b.name, b.visibility, b.status, b.is_hidden, b.close_scheduled_at, b.close_requested_by,
                       b.created_at, m.nickname AS owner, m.id AS owner_id,
                       (SELECT COUNT(*) FROM blog_memberships bm WHERE bm.blog_id = b.id AND bm.status = 'ACTIVE') AS members,
                       (SELECT COUNT(*) FROM posts x WHERE x.blog_id = b.id AND x.status = 'NORMAL') AS posts,
                       (SELECT COUNT(*) FROM owner_warnings w WHERE w.blog_id = b.id AND w.member_id = b.owner_id
                          AND w.created_at >= :yearAgo) AS warnings
                FROM blogs b JOIN members m ON m.id = b.owner_id""" + where + " ORDER BY b.id DESC", p, page,
                (rs, i) -> new BlogItem(rs.getLong("id"), rs.getString("slug"), rs.getString("name"), rs.getString("visibility"),
                        rs.getString("status"), rs.getBoolean("is_hidden"), time(rs.getObject("close_scheduled_at", LocalDateTime.class)),
                        rs.getString("close_requested_by"), time(rs.getObject("created_at", LocalDateTime.class)),
                        rs.getLong("owner_id"), rs.getString("owner"), rs.getInt("members"), rs.getInt("posts"), rs.getInt("warnings")));
    }

    // ----- 글·댓글 (ADM-03) -----

    @Transactional(readOnly = true)
    public Page<PostItem> posts(String q, String status, int page) {
        MapSqlParameterSource p = new MapSqlParameterSource("q", like(q)).addValue("status", status);
        List<String> w = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            w.add("(p.title LIKE :q OR b.slug LIKE :q OR m.nickname LIKE :q)");
        }
        if (status != null && !status.isBlank()) {
            w.add("p.status = :status");
        }
        String where = w.isEmpty() ? "" : " WHERE " + String.join(" AND ", w);
        String from = " FROM posts p JOIN blogs b ON b.id = p.blog_id JOIN members m ON m.id = p.author_id";
        return page("SELECT COUNT(*)" + from + where, """
                SELECT p.id, p.title, p.status, p.deleted_by, p.deleted_at, p.created_at, b.slug, b.name AS blog_name,
                       m.nickname, m.status AS member_status""" + from + where + " ORDER BY p.id DESC", p, page,
                (rs, i) -> new PostItem(rs.getLong("id"), rs.getString("title"), rs.getString("status"), rs.getString("deleted_by"),
                        time(rs.getObject("deleted_at", LocalDateTime.class)), time(rs.getObject("created_at", LocalDateTime.class)),
                        rs.getString("slug"), rs.getString("blog_name"),
                        shownNickname(rs.getString("nickname"), rs.getString("member_status")),
                        "/blog/" + rs.getString("slug") + "/posts/" + rs.getLong("id")));
    }

    @Transactional(readOnly = true)
    public Page<CommentItem> comments(String q, String status, int page) {
        MapSqlParameterSource p = new MapSqlParameterSource("q", like(q)).addValue("status", status);
        List<String> w = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            w.add("(c.content LIKE :q OR m.nickname LIKE :q)");
        }
        if (status != null && !status.isBlank()) {
            w.add("c.status = :status");
        }
        String where = w.isEmpty() ? "" : " WHERE " + String.join(" AND ", w);
        String from = " FROM comments c JOIN posts p ON p.id = c.post_id JOIN blogs b ON b.id = p.blog_id JOIN members m ON m.id = c.author_id";
        return page("SELECT COUNT(*)" + from + where, """
                SELECT c.id, LEFT(c.content, 100) AS content, c.status, c.created_at, p.id AS post_id, p.title, b.slug,
                       m.nickname, m.status AS member_status""" + from + where + " ORDER BY c.id DESC", p, page,
                (rs, i) -> new CommentItem(rs.getLong("id"), rs.getString("content"), rs.getString("status"),
                        time(rs.getObject("created_at", LocalDateTime.class)), rs.getString("title"), rs.getString("slug"),
                        shownNickname(rs.getString("nickname"), rs.getString("member_status")),
                        "/blog/" + rs.getString("slug") + "/posts/" + rs.getLong("post_id") + "#comment-" + rs.getLong("id")));
    }

    public enum ContentAction { HIDE, UNHIDE, DELETE, RESTORE }

    /**
     * 글 숨김·숨김 해제·삭제·되살리기. 숨긴 글은 글쓴이와 독자 모두에게 보이지 않고, 삭제는 30일 뒤 완전히 지워진다.
     * 되살리기는 관리자가 지운 글을 30일 안에만 할 수 있다.
     */
    @Transactional
    public void post(long adminId, long postId, ContentAction action, String reason) {
        Map<String, Object> row = jdbc.queryForList("SELECT blog_id, author_id, status, deleted_by FROM posts WHERE id = :id FOR UPDATE",
                Map.of("id", postId)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "글을 찾을 수 없습니다."));
        String status = (String) row.get("status");
        long blogId = ((Number) row.get("blog_id")).longValue();
        long authorId = ((Number) row.get("author_id")).longValue();
        LocalDateTime now = LocalDateTime.now(clock);
        MapSqlParameterSource p = new MapSqlParameterSource("id", postId).addValue("now", now);
        switch (action) {
            case HIDE -> {
                requireStatus(status, "NORMAL", "보이는 글만 숨길 수 있습니다.");
                jdbc.update("UPDATE posts SET status = 'HIDDEN' WHERE id = :id", p);
                notifications.post(authorId, NotificationWriter.Type.POST_HIDDEN, blogId, null, postId);
                adminLog.write(adminId, AdminLog.Action.HIDE, "POST", postId, reason);
            }
            case UNHIDE -> {
                requireStatus(status, "HIDDEN", "숨긴 글이 아닙니다.");
                jdbc.update("UPDATE posts SET status = 'NORMAL' WHERE id = :id", p);
                adminLog.write(adminId, AdminLog.Action.UNHIDE, "POST", postId, reason);
            }
            case DELETE -> {
                if ("DELETED".equals(status)) {
                    throw new ApiException(HttpStatus.CONFLICT, "ALREADY_DELETED", "이미 지운 글입니다.");
                }
                jdbc.update("UPDATE posts SET status = 'DELETED', deleted_by = 'ADMIN', deleted_at = :now WHERE id = :id", p);
                notifications.post(authorId, NotificationWriter.Type.POST_DELETED, blogId, null, postId);
                adminLog.write(adminId, AdminLog.Action.DELETE, "POST", postId, reason);
            }
            case RESTORE -> {
                requireStatus(status, "DELETED", "지운 글이 아닙니다.");
                if (!"ADMIN".equals(row.get("deleted_by"))) {
                    throw new ApiException(HttpStatus.CONFLICT, "NOT_ADMIN_DELETED", "관리자가 지운 글만 되살릴 수 있습니다.");
                }
                jdbc.update("UPDATE posts SET status = 'NORMAL', deleted_by = NULL, deleted_at = NULL WHERE id = :id", p);
                adminLog.write(adminId, AdminLog.Action.RESTORE, "POST", postId, reason);
            }
        }
    }

    /** 댓글 숨김·숨김 해제·삭제. 숨기거나 지운 댓글은 "삭제된 댓글"로 보인다. 되살리기는 숨김 해제로 한다. */
    @Transactional
    public void comment(long adminId, long commentId, ContentAction action, String reason) {
        Map<String, Object> row = jdbc.queryForList("SELECT post_id, status FROM comments WHERE id = :id FOR UPDATE",
                Map.of("id", commentId)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다."));
        String status = (String) row.get("status");
        MapSqlParameterSource p = new MapSqlParameterSource("id", commentId).addValue("post", row.get("post_id"))
                .addValue("now", LocalDateTime.now(clock));
        String countDown = "UPDATE posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = :post";
        switch (action) {
            case HIDE -> {
                requireStatus(status, "NORMAL", "보이는 댓글만 숨길 수 있습니다.");
                jdbc.update("UPDATE comments SET status = 'HIDDEN', updated_at = :now WHERE id = :id", p);
                jdbc.update(countDown, p);
                adminLog.write(adminId, AdminLog.Action.HIDE, "COMMENT", commentId, reason);
            }
            case UNHIDE, RESTORE -> {
                requireStatus(status, "HIDDEN", "숨긴 댓글이 아닙니다.");
                jdbc.update("UPDATE comments SET status = 'NORMAL', updated_at = :now WHERE id = :id", p);
                jdbc.update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = :post", p);
                adminLog.write(adminId, AdminLog.Action.UNHIDE, "COMMENT", commentId, reason);
            }
            case DELETE -> {
                if ("DELETED".equals(status)) {
                    throw new ApiException(HttpStatus.CONFLICT, "ALREADY_DELETED", "이미 지운 댓글입니다.");
                }
                jdbc.update("UPDATE comments SET status = 'DELETED', updated_at = :now WHERE id = :id", p);
                if ("NORMAL".equals(status)) {
                    jdbc.update(countDown, p);
                }
                adminLog.write(adminId, AdminLog.Action.DELETE, "COMMENT", commentId, reason);
            }
        }
    }

    private static void requireStatus(String status, String expected, String message) {
        if (!expected.equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS", message);
        }
    }

    // ----- 통계 (ADM-05) -----

    @Transactional(readOnly = true)
    public Stats stats() {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(13);
        MapSqlParameterSource p = new MapSqlParameterSource("from", from.atStartOfDay());
        Map<String, Integer> signups = byDay("SELECT DATE(created_at) AS d, COUNT(*) AS n FROM members WHERE created_at >= :from GROUP BY d", p);
        Map<String, Integer> posts = byDay("SELECT DATE(created_at) AS d, COUNT(*) AS n FROM posts WHERE created_at >= :from GROUP BY d", p);
        List<Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            days.add(new Day(d.toString(), signups.getOrDefault(d.toString(), 0), posts.getOrDefault(d.toString(), 0)));
        }
        return new Stats(count("SELECT COUNT(*) FROM members WHERE status = 'ACTIVE'"),
                count("SELECT COUNT(*) FROM blogs WHERE status IN ('ACTIVE', 'CLOSING')"),
                count("SELECT COUNT(*) FROM posts WHERE status = 'NORMAL'"),
                count("SELECT COUNT(*) FROM reports WHERE handler = 'ADMIN' AND status = 'PENDING'"), days);
    }

    private Map<String, Integer> byDay(String sql, MapSqlParameterSource p) {
        Map<String, Integer> out = new java.util.HashMap<>();
        jdbc.query(sql, p, rs -> {
            out.put(rs.getDate("d").toLocalDate().toString(), rs.getInt("n"));
        });
        return out;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    // ----- 활동 기록 (ADM-06) -----

    @Transactional(readOnly = true)
    public Page<LogItem> logs(int page) {
        return page("SELECT COUNT(*) FROM admin_action_logs", """
                SELECT l.id, a.login_id, l.action, l.target_type, l.target_id, l.reason, l.created_at
                FROM admin_action_logs l JOIN admins a ON a.id = l.admin_id ORDER BY l.id DESC""",
                new MapSqlParameterSource(), page,
                (rs, i) -> new LogItem(rs.getLong("id"), rs.getString("login_id"), rs.getString("action"),
                        rs.getString("target_type"), rs.getLong("target_id"), rs.getString("reason"),
                        time(rs.getObject("created_at", LocalDateTime.class))));
    }

    // ----- 공통 -----

    private <T> Page<T> page(String countSql, String sql, MapSqlParameterSource p, int page, RowMapper<T> mapper) {
        Long total = jdbc.queryForObject(countSql, p, Long.class);
        long count = total == null ? 0 : total;
        int current = Math.max(page, 1);
        p.addValue("size", PAGE_SIZE).addValue("offset", (current - 1) * PAGE_SIZE);
        List<T> items = jdbc.query(sql + " LIMIT :size OFFSET :offset", p, mapper);
        return new Page<>(items, current, (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE), count);
    }

    private static String like(String q) {
        if (q == null) {
            return "%";
        }
        String s = q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + (s.length() > 50 ? s.substring(0, 50) : s) + "%";
    }

    private static String shownNickname(String nickname, String status) {
        return "ACTIVE".equals(status) ? nickname : nickname + " (탈퇴)";
    }

    static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.length() == 1 ? "*" : name.charAt(0) + "*".repeat(name.length() - 1);
    }

    private static String time(LocalDateTime t) {
        return t == null ? null : t.format(DATE_TIME);
    }

    public record Page<T>(List<T> items, int page, int totalPages, long total) {
    }

    public record MemberItem(long id, String nickname, String email, String status, String createdAt, int blogs, int posts) {
    }

    public record MemberBlog(String slug, String name, String role, String status, String suspendedUntil, int warnings,
            int suspensions) {
    }

    public record MemberDetail(long id, String nickname, String name, String email, String phone, String status,
            String createdAt, String withdrawnAt, List<MemberBlog> blogs) {
    }

    public record Pii(String name, String email, String phone) {
    }

    public record BlogItem(long id, String slug, String name, String visibility, String status, boolean hidden,
            String closeScheduledAt, String closeRequestedBy, String createdAt, long ownerId, String ownerNickname,
            int members, int posts, int ownerWarnings) {
    }

    public record PostItem(long id, String title, String status, String deletedBy, String deletedAt, String createdAt,
            String blogSlug, String blogName, String author, String link) {
    }

    public record CommentItem(long id, String content, String status, String createdAt, String postTitle, String blogSlug,
            String author, String link) {
    }

    public record Day(String date, int signups, int posts) {
    }

    public record Stats(long members, long blogs, long posts, long pendingReports, List<Day> days) {
    }

    public record LogItem(long id, String admin, String action, String targetType, long targetId, String reason,
            String createdAt) {
    }
}
