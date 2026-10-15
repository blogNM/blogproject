package com.blognm.mainblog.board;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.blognm.mainblog.post.MarkdownRenderer;

/**
 * 메인 피드와 통합 검색이 함께 쓰는 글 카드 목록.
 * 공개 블로그(숨김·폐쇄 제외)의 정상 글만 나온다. 일부 공개·비공개 블로그의 글은 나오지 않는다 (004 Edge Cases).
 * 로그인한 회원에게는 서로 차단한 회원의 글을 빼고 보여준다 (SC-005, 추천안 #10 양방향).
 */
@Component
public class PostCards {

    public static final Set<Integer> PAGE_SIZES = Set.of(10, 20, 30);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final int EXCERPT_MAX = 120;

    private final NamedParameterJdbcTemplate jdbc;
    private final MarkdownRenderer markdown;

    public PostCards(NamedParameterJdbcTemplate jdbc, MarkdownRenderer markdown) {
        this.jdbc = jdbc;
        this.markdown = markdown;
    }

    /** 공개된 글 조건. 별칭은 글 p, 블로그 b. */
    public static String publicWhere(Long viewerId, MapSqlParameterSource params) {
        String where = """
                p.status = 'NORMAL' AND b.visibility = 'PUBLIC' AND b.is_hidden = FALSE
                AND b.status IN ('ACTIVE', 'CLOSING')
                """;
        if (viewerId == null) {
            return where;
        }
        params.addValue("viewerId", viewerId);
        return where + """
                AND NOT EXISTS (SELECT 1 FROM blocks bk
                                WHERE (bk.blocker_id = :viewerId AND bk.blocked_id = p.author_id)
                                   OR (bk.blocker_id = p.author_id AND bk.blocked_id = :viewerId))
                """;
    }

    private static final String POSTS_AND_BLOGS = " FROM posts p JOIN blogs b ON b.id = p.blog_id";
    private static final Pattern USES_MEMBER = Pattern.compile("\\bm\\.");

    public Page page(String where, String orderBy, MapSqlParameterSource params, int page, int size) {
        return page(POSTS_AND_BLOGS, where, orderBy, params, page, size, null);
    }

    /** 건수를 이미 센 목록 (통합 검색). 같은 조건의 COUNT를 다시 돌리지 않는다. */
    public Page page(String where, String orderBy, MapSqlParameterSource params, int page, int size, long total) {
        return page(POSTS_AND_BLOGS, where, orderBy, params, page, size, total);
    }

    /**
     * 조건 없이 최신순으로 보는 메인 피드. 글을 쓴 시각 순서의 인덱스를 따라 읽다가 한 쪽을 채우면 멈춘다.
     * 그냥 두면 MySQL이 공개 블로그부터 읽고 공개 글을 전부 모아 정렬해서, 글이 많아질수록 느려진다 (대량 데이터 확인, 2026-10-08).
     */
    public Page latestPage(String where, MapSqlParameterSource params, int page, int size) {
        return page(" FROM posts p FORCE INDEX (idx_posts_status_created) STRAIGHT_JOIN blogs b ON b.id = p.blog_id",
                where, "p.created_at DESC, p.id DESC", params, page, size, null);
    }

    private Page page(String postsAndBlogs, String where, String orderBy, MapSqlParameterSource params, int page, int size,
            Long knownTotal) {
        int pageSize = PAGE_SIZES.contains(size) ? size : 10;
        int current = Math.max(page, 1);
        // 건수는 블로그부터 읽는 편이 빠르고, 닉네임 조건이 없으면 회원 표를 붙이지 않는다
        String countFrom = POSTS_AND_BLOGS + (USES_MEMBER.matcher(where).find() ? " JOIN members m ON m.id = p.author_id" : "");
        Long total = knownTotal != null ? knownTotal
                : jdbc.queryForObject("SELECT COUNT(*)" + countFrom + " WHERE " + where, params, Long.class);
        String from = postsAndBlogs + " JOIN members m ON m.id = p.author_id WHERE ";
        params.addValue("size", pageSize).addValue("offset", (long) (current - 1) * pageSize);
        List<Card> items = jdbc.query("""
                SELECT p.id, p.title, p.content_md, p.created_at, p.view_count, p.like_count, p.comment_count,
                       p.author_left, p.author_id, b.slug, b.name AS blog_name, b.theme_accent, m.nickname, m.status AS member_status, m.is_test,
                       (SELECT i.stored_name FROM images i WHERE i.post_id = p.id ORDER BY i.sort_order, i.id LIMIT 1) AS first_image
                """ + from + where + " ORDER BY " + orderBy + " LIMIT :size OFFSET :offset", params,
                (rs, i) -> new Card(rs.getLong("id"), rs.getString("slug"), rs.getString("blog_name"),
                        rs.getString("theme_accent"), rs.getString("title"),
                        markdown.toPlainText(rs.getString("content_md"), EXCERPT_MAX),
                        rs.getBoolean("author_left") ? "탈퇴한 계정"
                                : "ACTIVE".equals(rs.getString("member_status")) ? rs.getString("nickname") : "탈퇴한 회원",
                        rs.getObject("created_at", LocalDateTime.class).format(DATE_TIME),
                        rs.getLong("view_count"), rs.getLong("like_count"), rs.getLong("comment_count"),
                        rs.getString("first_image") == null ? null : "/uploads/" + rs.getString("first_image"),
                        new ArrayList<>(),
                        rs.getBoolean("author_left") || !"ACTIVE".equals(rs.getString("member_status")) ? null
                                : rs.getLong("author_id"),
                        shownTest(rs.getBoolean("author_left"), rs.getString("member_status"), rs.getBoolean("is_test"))));
        attachTags(items);
        long count = total == null ? 0 : total;
        return new Page(items, current, pageSize, (int) ((count + pageSize - 1) / pageSize), count);
    }

    private void attachTags(List<Card> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Long, Card> byId = new HashMap<>();
        items.forEach(c -> byId.put(c.id(), c));
        jdbc.query("""
                SELECT pt.post_id, t.name FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
                WHERE pt.post_id IN (:ids) ORDER BY t.name
                """, Map.of("ids", byId.keySet()), rs -> {
                    byId.get(rs.getLong("post_id")).tags().add(rs.getString("name"));
                });
    }

    /** 작성자 이름 옆 "테스트" 표시 (D-113). 탈퇴한 회원·블로그를 나간 작성자는 이름이 안 보이므로 표시도 없다. */
    public static boolean shownTest(boolean authorLeft, String memberStatus, boolean test) {
        return test && !authorLeft && "ACTIVE".equals(memberStatus);
    }

    /** 화면은 모든 값을 textContent로 넣는다. 요약(excerpt)도 HTML을 걷어 낸 글자다. authorTest: 테스트 회원 (D-113). */
    public record Card(long id, String blogSlug, String blogName, String accent, String title, String excerpt,
            String authorName, String createdAt, long views, long likes, long comments, String imageUrl,
            List<String> tags, Long authorId, boolean authorTest) {
    }

    public record Page(List<Card> items, int page, int size, int totalPages, long total) {
    }
}
