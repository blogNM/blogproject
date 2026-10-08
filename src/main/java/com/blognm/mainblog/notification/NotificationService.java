package com.blognm.mainblog.notification;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.common.ApiException;

/**
 * 알림 사이드바 (SOC-04, D-39, D-56). 탭은 전체(맨 앞), 댓글, 좋아요, 팔로우·구독, 블로그, 운영.
 * 문구와 이동할 주소는 서버가 만든다. 화면은 문구를 textContent로 넣는다 (헌법 III).
 * 읽은 알림은 강조 색만 빠지고 목록에 남는다. 보관 기간이 지나면 매일 04:00에 지운다 (NotificationCleanupJob).
 */
@Service
public class NotificationService {

    public static final Set<String> TABS = Set.of("ALL", "COMMENT", "LIKE", "FOLLOW", "BLOG", "ADMIN");
    private static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page list(long memberId, String rawTab, int page) {
        String tab = rawTab == null ? "ALL" : rawTab.toUpperCase();
        if (!TABS.contains(tab)) {
            throw ApiException.badRequest("INVALID_TAB", "없는 알림 탭입니다.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource("me", memberId).addValue("tab", tab);
        String where = " WHERE n.recipient_id = :me" + ("ALL".equals(tab) ? "" : " AND n.tab = :tab");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notifications n" + where, params, Long.class);
        int current = Math.max(page, 1);
        params.addValue("size", PAGE_SIZE).addValue("offset", (current - 1) * PAGE_SIZE);
        List<Item> items = jdbc.query("""
                SELECT n.id, n.type, n.tab, n.target_type, n.target_id, n.detail, n.is_read, n.created_at,
                       a.nickname AS actor, a.status AS actor_status, b.slug, b.name AS blog_name,
                       CASE n.target_type
                         WHEN 'POST' THEN (SELECT p.title FROM posts p WHERE p.id = n.target_id)
                         WHEN 'COMMENT' THEN (SELECT p.title FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.id = n.target_id)
                       END AS post_title,
                       CASE n.target_type
                         WHEN 'POST' THEN n.target_id
                         WHEN 'COMMENT' THEN (SELECT c.post_id FROM comments c WHERE c.id = n.target_id)
                       END AS post_id
                FROM notifications n
                LEFT JOIN members a ON a.id = n.actor_id
                LEFT JOIN blogs b ON b.id = n.blog_id
                """ + where + " ORDER BY n.created_at DESC, n.id DESC LIMIT :size OFFSET :offset", params,
                (rs, i) -> {
                    String actor = rs.getString("actor") == null ? null
                            : "ACTIVE".equals(rs.getString("actor_status")) ? rs.getString("actor") : "탈퇴한 회원";
                    Long postId = rs.getObject("post_id", Long.class);
                    Text text = text(rs.getString("type"), actor, rs.getString("slug"), rs.getString("blog_name"),
                            rs.getString("post_title"), postId, rs.getString("target_type"),
                            rs.getObject("target_id", Long.class), rs.getString("detail"));
                    return new Item(rs.getLong("id"), rs.getString("tab"), text.message(), text.link(),
                            rs.getBoolean("is_read"), rs.getObject("created_at", LocalDateTime.class).format(DATE_TIME));
                });
        long count = total == null ? 0 : total;
        return new Page(items, current, (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE), count, unread(memberId));
    }

    /** 종 아이콘 숫자. 30초마다 부른다 (D-72). 탭별 숫자도 같이 준다. */
    @Transactional(readOnly = true)
    public Map<String, Long> unread(long memberId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", 0L);
        jdbc.query("SELECT tab, COUNT(*) AS n FROM notifications WHERE recipient_id = :me AND is_read = FALSE GROUP BY tab",
                Map.of("me", memberId), rs -> {
                    counts.put(rs.getString("tab"), rs.getLong("n"));
                    counts.merge("ALL", rs.getLong("n"), Long::sum);
                });
        return counts;
    }

    @Transactional
    public void read(long memberId, long id) {
        jdbc.update("UPDATE notifications SET is_read = TRUE WHERE id = :id AND recipient_id = :me",
                Map.of("id", id, "me", memberId));
    }

    /** 지금 보고 있는 탭의 알림을 모두 읽음으로. */
    @Transactional
    public void readAll(long memberId, String tab) {
        jdbc.update("UPDATE notifications SET is_read = TRUE WHERE recipient_id = :me AND is_read = FALSE"
                + tabCondition(tab), new MapSqlParameterSource("me", memberId).addValue("tab", tab));
    }

    /** 지금 보고 있는 탭의 알림을 모두 지운다. */
    @Transactional
    public void deleteAll(long memberId, String tab) {
        jdbc.update("DELETE FROM notifications WHERE recipient_id = :me" + tabCondition(tab),
                new MapSqlParameterSource("me", memberId).addValue("tab", tab));
    }

    // ----- 설정 (내 정보 > 알림) -----

    @Transactional(readOnly = true)
    public Settings settings(long memberId) {
        Map<String, Boolean> off = new LinkedHashMap<>();
        jdbc.query("SELECT type, enabled FROM notification_settings WHERE member_id = :me", Map.of("me", memberId),
                rs -> {
                    off.put(rs.getString("type"), rs.getBoolean("enabled"));
                });
        List<Setting> items = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (NotificationWriter.Type t : NotificationWriter.Type.values()) {
            if (t.switchable && seen.add(t.setting)) {
                items.add(new Setting(t.setting, t.label, off.getOrDefault(t.setting, true)));
            }
        }
        Integer days = jdbc.queryForObject("SELECT notification_retention_days FROM members WHERE id = :me",
                Map.of("me", memberId), Integer.class);
        return new Settings(items, NotificationWriter.ALWAYS_ON, days == null ? 30 : days);
    }

    /** 끌 수 없는 알림은 어떤 요청으로도 꺼지지 않는다 (SC-002). */
    @Transactional
    public Settings updateSettings(long memberId, Map<String, Boolean> enabled, Integer retentionDays) {
        Set<String> switchable = new java.util.HashSet<>();
        for (NotificationWriter.Type t : NotificationWriter.Type.values()) {
            if (t.switchable) {
                switchable.add(t.setting);
            }
        }
        if (enabled != null) {
            for (Map.Entry<String, Boolean> e : enabled.entrySet()) {
                if (!switchable.contains(e.getKey()) || e.getValue() == null) {
                    throw ApiException.badRequest("INVALID_SETTING", "끌 수 없는 알림입니다.");
                }
                jdbc.update("""
                        INSERT INTO notification_settings (member_id, type, enabled) VALUES (:me, :type, :on)
                        ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)
                        """, Map.of("me", memberId, "type", e.getKey(), "on", e.getValue()));
            }
        }
        if (retentionDays != null) {
            jdbc.update("UPDATE members SET notification_retention_days = :days WHERE id = :me",
                    Map.of("days", retention(retentionDays), "me", memberId));
        }
        return settings(memberId);
    }

    /** 블로그 탭 알림 보관 (블로그장, 블로그 관리 > 알림). */
    @Transactional
    public void updateBlogRetention(String slug, int days) {
        jdbc.update("UPDATE blogs SET notification_retention_days = :days WHERE slug = :slug",
                Map.of("days", retention(days), "slug", slug));
    }

    @Transactional(readOnly = true)
    public int blogRetention(String slug) {
        Integer days = jdbc.queryForObject("SELECT notification_retention_days FROM blogs WHERE slug = :slug",
                Map.of("slug", slug), Integer.class);
        return days == null ? 30 : days;
    }

    private static int retention(int days) {
        if (days != 7 && days != 30) {
            throw ApiException.badRequest("INVALID_RETENTION", "알림 보관은 30일 또는 7일 중에서 고를 수 있습니다.");
        }
        return days;
    }

    private static String tabCondition(String tab) {
        if (tab == null || "ALL".equals(tab)) {
            return "";
        }
        if (!TABS.contains(tab)) {
            throw ApiException.badRequest("INVALID_TAB", "없는 알림 탭입니다.");
        }
        return " AND tab = :tab";
    }

    /** 알림 문구와 누르면 갈 주소. 대상이 지워졌으면 주소 없이 문구만 남는다. */
    static Text text(String type, String actor, String slug, String blogName, String postTitle, Long postId,
            String targetType, Long targetId) {
        return text(type, actor, slug, blogName, postTitle, postId, targetType, targetId, null);
    }

    static Text text(String type, String actor, String slug, String blogName, String postTitle, Long postId,
            String targetType, Long targetId, String detail) {
        String d = detail == null ? "" : detail;
        String who = actor == null ? "누군가가" : actor + "님이";
        String blog = blogName == null ? "블로그" : "'" + blogName + "'";
        String title = postTitle == null ? "글" : "'" + postTitle + "'";
        String postLink = slug == null || postId == null ? null : "/blog/" + slug + "/posts/" + postId;
        String commentLink = postLink == null || !"COMMENT".equals(targetType) ? postLink
                : postLink + "#comment-" + targetId;
        String blogLink = slug == null ? null : "/blog/" + slug;
        return switch (type) {
            case "COMMENT" -> new Text(who + " 내 글 " + title + "에 댓글을 달았습니다.", commentLink);
            case "REPLY" -> new Text(who + " 내 댓글에 답글을 달았습니다.", commentLink);
            case "LIKE" -> new Text(who + " 내 글 " + title + "에 좋아요를 눌렀습니다.", postLink);
            case "NEW_FOLLOWER" -> new Text(who + " 나를 팔로우합니다.", targetId == null ? null : "/members/" + targetId);
            case "BLOG_SUBSCRIBED" -> new Text(who + " " + blog + " 블로그를 구독합니다.", blogLink);
            case "JOIN_REQUESTED" -> new Text(who + " " + blog + " 블로그에 참여를 신청했습니다.",
                    blogLink == null ? null : blogLink + "/manage");
            case "JOIN_APPROVED" -> new Text(blog + " 블로그 참여가 승인되었습니다.", blogLink);
            case "JOIN_REJECTED" -> new Text(blog + " 블로그 참여 신청이 거절되었습니다.", blogLink);
            case "POST_DELETED" -> new Text(blog + " 블로그에서 내 글이 삭제되었습니다: " + title, null);
            case "REPORT_RESULT" -> new Text(detail == null ? "신고하신 내용의 처리가 끝났습니다."
                    : "신고하신 내용을 처리했습니다: " + d, null);
            case "POST_HIDDEN" -> new Text("운영 정책에 따라 내 글 " + title + "이(가) 숨겨졌습니다.", null);
            case "TRANSFER_REQUESTED" -> new Text(who + " " + blog + " 블로그의 블로그장을 넘기려고 합니다. 7일 안에 수락하거나 거절해 주세요.", blogLink);
            case "TRANSFER_ACCEPTED" -> new Text(who + " " + blog + " 블로그장 위임을 수락했습니다.", blogLink);
            case "TRANSFER_REJECTED" -> new Text(who + " " + blog + " 블로그장 위임을 거절했습니다.", blogLink);
            case "TRANSFER_EXPIRED" -> new Text(blog + " 블로그장 위임 요청이 7일 동안 답이 없어 취소되었습니다.", blogLink);
            case "OWNER_PROMOTED" -> new Text("이제 내가 " + blog + " 블로그의 블로그장입니다.", blogLink);
            case "BLOG_CLOSING" -> new Text(blog + " 블로그가 " + d + " 04:00에 폐쇄됩니다.", blogLink);
            case "BLOG_CLOSING_D3" -> new Text(blog + " 블로그가 3일 뒤(" + d + " 04:00) 폐쇄됩니다.", blogLink);
            case "BLOG_CLOSING_D1" -> new Text(blog + " 블로그가 내일(" + d + " 04:00) 폐쇄됩니다.", blogLink);
            case "BLOG_CLOSING_ADMIN" -> new Text("운영 정책에 따라 " + blog + " 블로그가 " + d + " 04:00에 폐쇄됩니다.", blogLink);
            case "BLOG_CLOSING_KICK" -> new Text("블로그장 권한 박탈로 인한 폐쇄 조치: " + blog + " 블로그가 " + d
                    + " 04:00에 폐쇄됩니다.", blogLink);
            case "BLOG_CLOSE_CANCELLED" -> new Text(blog + " 블로그 폐쇄가 철회되었습니다.", blogLink);
            case "MEMBER_WARNED" -> new Text(blog + " 블로그에서 경고를 받았습니다.", blogLink);
            case "MEMBER_SUSPENDED" -> new Text(blog + " 블로그에서 활동이 정지되었습니다 (" + d + ").", null);
            case "MEMBER_UNSUSPENDED" -> new Text(blog + " 블로그의 활동 정지가 풀렸습니다.", blogLink);
            case "MEMBER_KICKED" -> new Text(blog + " 블로그에서 강제 퇴장되었습니다.", null);
            case "OWNER_WARNED" -> new Text(blog + " 블로그장으로서 운영 경고를 받았습니다 (" + d + "). 3번이면 블로그장 권한이 박탈됩니다.", blogLink);
            case "OWNER_KICKED" -> new Text(blog + " 블로그의 블로그장 권한이 박탈되었습니다. 일반 멤버로 남습니다.", blogLink);
            case "BLOG_HIDDEN" -> new Text("운영 정책에 따라 " + blog + " 블로그가 숨겨졌습니다. 멤버만 볼 수 있습니다.", blogLink);
            case "BLOG_UNHIDDEN" -> new Text(blog + " 블로그 숨김이 풀렸습니다.", blogLink);
            case "BLACKLIST_APPEAL" -> new Text(who + " " + blog + " 블로그 블랙리스트 해제를 문의했습니다.",
                    blogLink == null ? null : blogLink + "/manage#blacklist");
            case "BLACKLIST_LIFTED" -> new Text(blog + " 블로그 블랙리스트에서 해제되었습니다. 다시 참여를 신청할 수 있습니다.", blogLink);
            case "BLACKLIST_REJECTED" -> new Text(blog + " 블로그 블랙리스트 해제 문의가 거절되었습니다.", null);
            case "NOTICE" -> new Text("새 공지사항이 있습니다.", "/");
            default -> new Text("새 알림이 있습니다.", null);
        };
    }

    record Text(String message, String link) {
    }

    /** link가 null이면 누를 곳이 없는 알림이다. */
    public record Item(long id, String tab, String message, String link, boolean read, String createdAt) {
    }

    public record Page(List<Item> items, int page, int totalPages, long total, Map<String, Long> unread) {
    }

    public record Setting(String type, String label, boolean enabled) {
    }

    public record Settings(List<Setting> items, List<String> alwaysOn, int retentionDays) {
    }
}
