package com.blognm.mainblog.post;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.blognm.mainblog.blog.Blog;
import com.blognm.mainblog.blog.BlogRules;
import com.blognm.mainblog.blog.BlogService;

/**
 * 블로그 안 글 목록 (BRD-02·03·04). 최신순, 번호 페이지, 10·20·30개 (D-06, D-76).
 * 공지는 목록 위에 따로 보여준다. 읽기만 하므로 SQL로 바로 읽는다.
 */
@Service
public class PostQueryService {

    public static final Set<Integer> PAGE_SIZES = Set.of(10, 20, 30);
    private static final int NOTICE_MAX = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final BlogService blogService;

    public PostQueryService(NamedParameterJdbcTemplate jdbc, BlogService blogService) {
        this.jdbc = jdbc;
        this.blogService = blogService;
    }

    public PostPage list(String slug, String key, Long viewerId, int page, int size, Long categoryId, String rawTag) {
        Blog blog = blogService.visibleBlog(slug, key, viewerId);
        int pageSize = PAGE_SIZES.contains(size) ? size : 10;
        int current = Math.max(page, 1);
        MapSqlParameterSource params = new MapSqlParameterSource("blogId", blog.getId());
        StringBuilder where = new StringBuilder("p.blog_id = :blogId AND p.status = 'NORMAL'");
        boolean filtered = false;
        if (categoryId != null) {
            where.append(" AND p.category_id = :categoryId");
            params.addValue("categoryId", categoryId);
            filtered = true;
        }
        String tag = rawTag == null || rawTag.isBlank() ? null : BlogRules.normalizeTag(rawTag);
        if (rawTag != null && !rawTag.isBlank()) {
            if (tag == null) {
                return new PostPage(List.of(), List.of(), current, pageSize, 0, 0, null);
            }
            where.append(" AND p.id IN (SELECT pt.post_id FROM post_tags pt JOIN tags t ON t.id = pt.tag_id WHERE t.name = :tag)");
            params.addValue("tag", tag);
            filtered = true;
        }

        List<PostSummary> notices = filtered || current > 1 ? List.of()
                : query("p.blog_id = :blogId AND p.status = 'NORMAL' AND p.is_notice = TRUE", params,
                        "LIMIT " + NOTICE_MAX);
        if (!filtered) {
            where.append(" AND p.is_notice = FALSE");
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM posts p WHERE " + where, params, Long.class);
        params.addValue("size", pageSize).addValue("offset", (long) (current - 1) * pageSize);
        List<PostSummary> items = query(where.toString(), params, "LIMIT :size OFFSET :offset");
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new PostPage(notices, items, current, pageSize, totalPages, total, tag);
    }

    /** 카테고리와 글 수. 글 목록 옆에 보여준다. */
    public List<CategoryRow> categories(String slug, String key, Long viewerId) {
        Blog blog = blogService.visibleBlog(slug, key, viewerId);
        return jdbc.query("""
                SELECT c.id, c.name,
                       (SELECT COUNT(*) FROM posts p WHERE p.category_id = c.id AND p.status = 'NORMAL') AS post_count
                FROM categories c WHERE c.blog_id = :blogId ORDER BY c.sort_order, c.id
                """, Map.of("blogId", blog.getId()),
                (rs, i) -> new CategoryRow(rs.getLong("id"), rs.getString("name"), rs.getLong("post_count")));
    }

    private List<PostSummary> query(String where, MapSqlParameterSource params, String limit) {
        List<PostSummary> items = jdbc.query("""
                SELECT p.id, p.title, p.is_notice, p.author_left, p.created_at, p.view_count, p.like_count,
                       p.comment_count, c.name AS category_name, m.nickname, m.status AS member_status,
                       (SELECT i.stored_name FROM images i WHERE i.post_id = p.id
                        ORDER BY i.sort_order, i.id LIMIT 1) AS first_image
                FROM posts p
                JOIN members m ON m.id = p.author_id
                LEFT JOIN categories c ON c.id = p.category_id
                WHERE %s
                ORDER BY p.created_at DESC, p.id DESC
                %s
                """.formatted(where, limit), params, (rs, i) -> new PostSummary(
                rs.getLong("id"), rs.getString("title"), rs.getBoolean("is_notice"),
                rs.getBoolean("author_left") ? "탈퇴한 계정"
                        : "ACTIVE".equals(rs.getString("member_status")) ? rs.getString("nickname") : "탈퇴한 회원",
                rs.getString("category_name"),
                rs.getObject("created_at", LocalDateTime.class).format(PostService.DATE_TIME),
                rs.getLong("view_count"), rs.getLong("like_count"), rs.getLong("comment_count"),
                rs.getString("first_image") == null ? null : "/uploads/" + rs.getString("first_image"),
                new ArrayList<>()));
        attachTags(items);
        return items;
    }

    private void attachTags(List<PostSummary> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Long, PostSummary> byId = new HashMap<>();
        items.forEach(p -> byId.put(p.id(), p));
        jdbc.query("""
                SELECT pt.post_id, t.name FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
                WHERE pt.post_id IN (:ids) ORDER BY t.name
                """, Map.of("ids", byId.keySet()), rs -> {
                    byId.get(rs.getLong("post_id")).tags().add(rs.getString("name"));
                });
    }

    public record PostSummary(long id, String title, boolean notice, String authorName, String categoryName,
            String createdAt, long views, long likes, long comments, String imageUrl, List<String> tags) {
    }

    public record PostPage(List<PostSummary> notices, List<PostSummary> items, int page, int size, int totalPages,
            long total, String tag) {
    }

    public record CategoryRow(long id, String name, long postCount) {
    }
}
