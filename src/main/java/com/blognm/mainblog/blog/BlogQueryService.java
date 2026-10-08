package com.blognm.mainblog.blog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;


/** 블로그 목록·검색 (BLG-02, BLG-03), 내 블로그 (BLG-06), 멤버 목록. 읽기만 하므로 SQL로 바로 읽는다. */
@Service
public class BlogQueryService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final NamedParameterJdbcTemplate jdbc;
    private final BlogProperties props;

    public BlogQueryService(NamedParameterJdbcTemplate jdbc, BlogProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /**
     * 공개 블로그만 보여준다. 일부 공개·비공개·숨김·폐쇄는 목록과 검색 어디에도 나오지 않는다 (SC-004).
     * 인기순은 구독자 수 순서다 (추천안 #6). 구독 기능이 생기기 전에는 사실상 최신순과 같다.
     * 검색은 이름·소개(FULLTEXT ngram)와 태그(정확히 같은 이름)로 찾는다.
     */
    public Page list(String sort, String rawQuery, int page) {
        int size = props.pageSize();
        int current = Math.max(page, 1);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder("""
                b.visibility = 'PUBLIC' AND b.is_hidden = FALSE AND b.status IN ('ACTIVE', 'CLOSING')
                """);

        String query = rawQuery == null ? "" : rawQuery.strip();
        if (!query.isEmpty()) {
            List<String> conditions = new ArrayList<>();
            String words = query.replaceAll("[+\\-<>()~*\"@]", " ").strip();
            if (words.codePointCount(0, words.length()) >= 2) {
                // ngram은 두 글자 단위라 한 글자 검색은 이름 LIKE로 찾는다.
                conditions.add("MATCH(b.name, b.description) AGAINST (:phrase IN BOOLEAN MODE)");
                params.addValue("phrase", "\"" + words + "\"");
            } else if (!words.isEmpty()) {
                conditions.add("b.name LIKE :like ESCAPE '!'");
                params.addValue("like", "%" + words.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            }
            String tag = BlogRules.normalizeTag(query);
            if (tag != null) {
                conditions.add("""
                        b.id IN (SELECT bt.blog_id FROM blog_tags bt JOIN tags t ON t.id = bt.tag_id WHERE t.name = :tag)
                        """);
                params.addValue("tag", tag);
            }
            if (conditions.isEmpty()) {
                return new Page(List.of(), current, 0, 0);
            }
            where.append(" AND (").append(String.join(" OR ", conditions)).append(")");
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM blogs b WHERE " + where, params, Long.class);
        String order = "popular".equals(sort)
                ? "subscriber_count DESC, b.id DESC"
                : "b.id DESC";
        params.addValue("size", size).addValue("offset", (long) (current - 1) * size);
        List<BlogSummary> items = jdbc.query("""
                SELECT b.id, b.slug, b.name, b.description, b.join_policy, b.status, b.created_at, b.theme_accent,
                       (SELECT i.stored_name FROM images i WHERE i.id = b.cover_image_id) AS cover,
                       m.nickname AS owner_nickname,
                       (SELECT COUNT(*) FROM blog_memberships x WHERE x.blog_id = b.id AND x.status = 'ACTIVE') AS member_count,
                       (SELECT COUNT(*) FROM subscriptions s WHERE s.blog_id = b.id) AS subscriber_count
                FROM blogs b JOIN members m ON m.id = b.owner_id
                WHERE %s
                ORDER BY %s
                LIMIT :size OFFSET :offset
                """.formatted(where, order), params, (rs, i) -> new BlogSummary(
                rs.getLong("id"), rs.getString("slug"), rs.getString("name"), rs.getString("description"),
                rs.getString("join_policy"), rs.getString("status"), null,
                rs.getString("owner_nickname"), rs.getLong("member_count"), rs.getLong("subscriber_count"),
                rs.getObject("created_at", LocalDateTime.class).format(DATE), coverUrl(rs.getString("cover")),
                rs.getString("theme_accent"), new ArrayList<>()));
        attachTags(items);
        int totalPages = (int) ((total + size - 1) / size);
        return new Page(items, current, totalPages, total);
    }

    /** 내가 만든 블로그와 참여한 블로그를 나눠 보여준다. 대기 중인 참여 신청도 함께 (BLG-06). */
    public MyBlogs myBlogs(long memberId) {
        List<BlogSummary> all = jdbc.query("""
                SELECT b.id, b.slug, b.name, b.description, b.join_policy, b.status, b.created_at, bm.role, b.theme_accent,
                       (SELECT i.stored_name FROM images i WHERE i.id = b.cover_image_id) AS cover,
                       m.nickname AS owner_nickname,
                       (SELECT COUNT(*) FROM blog_memberships x WHERE x.blog_id = b.id AND x.status = 'ACTIVE') AS member_count,
                       (SELECT COUNT(*) FROM subscriptions s WHERE s.blog_id = b.id) AS subscriber_count
                FROM blog_memberships bm
                JOIN blogs b ON b.id = bm.blog_id
                JOIN members m ON m.id = b.owner_id
                WHERE bm.member_id = :memberId AND bm.status = 'ACTIVE' AND b.status <> 'CLOSED'
                ORDER BY bm.joined_at DESC, b.id DESC
                """, Map.of("memberId", memberId), (rs, i) -> new BlogSummary(
                rs.getLong("id"), rs.getString("slug"), rs.getString("name"), rs.getString("description"),
                rs.getString("join_policy"), rs.getString("status"), rs.getString("role"),
                rs.getString("owner_nickname"), rs.getLong("member_count"), rs.getLong("subscriber_count"),
                rs.getObject("created_at", LocalDateTime.class).format(DATE), coverUrl(rs.getString("cover")),
                rs.getString("theme_accent"), new ArrayList<>()));
        attachTags(all);
        List<PendingBlog> pending = jdbc.query("""
                SELECT b.slug, b.name, r.requested_at
                FROM join_requests r JOIN blogs b ON b.id = r.blog_id
                WHERE r.member_id = :memberId AND r.status = 'PENDING' AND b.status <> 'CLOSED'
                ORDER BY r.id DESC
                """, Map.of("memberId", memberId), (rs, i) -> new PendingBlog(rs.getString("slug"),
                rs.getString("name"), rs.getObject("requested_at", LocalDateTime.class).format(DATE)));
        return new MyBlogs(
                all.stream().filter(b -> "OWNER".equals(b.myRole())).toList(),
                all.stream().filter(b -> !"OWNER".equals(b.myRole())).toList(),
                pending);
    }

    private static String coverUrl(String storedName) {
        return storedName == null ? null : "/uploads/" + storedName;
    }

    private void attachTags(List<BlogSummary> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Long, BlogSummary> byId = new HashMap<>();
        items.forEach(b -> byId.put(b.id(), b));
        jdbc.query("""
                SELECT bt.blog_id, t.name FROM blog_tags bt JOIN tags t ON t.id = bt.tag_id
                WHERE bt.blog_id IN (:ids) ORDER BY t.name
                """, Map.of("ids", byId.keySet()),
                rs -> {
                    byId.get(rs.getLong("blog_id")).tags().add(rs.getString("name"));
                });
    }

    public record BlogSummary(long id, String slug, String name, String description, String joinPolicy,
            String status, String myRole, String ownerNickname, long memberCount, long subscriberCount,
            String createdAt, String coverUrl, String accent, List<String> tags) {
    }

    public record Page(List<BlogSummary> items, int page, int totalPages, long total) {
    }

    public record PendingBlog(String slug, String name, String requestedAt) {
    }

    public record MyBlogs(List<BlogSummary> owned, List<BlogSummary> joined, List<PendingBlog> pending) {
    }

}
