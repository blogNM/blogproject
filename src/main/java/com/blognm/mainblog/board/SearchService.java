package com.blognm.mainblog.board;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.blog.BlogRules;
import com.blognm.mainblog.common.ApiException;

/**
 * 통합 검색 (BRD-08, 004 US6). 블로그(이름·소개), 글 제목은 FULLTEXT + ngram, 태그·작성자 닉네임은 정확히 같은 값으로 찾는다.
 * 분류바로 대상을 나누고, 분류마다 결과 수를 함께 보여준다. 공개 블로그의 글만 나온다 (PostCards).
 */
@Service
public class SearchService {

    public enum Type { POST, BLOG, TAG, NICKNAME }

    public enum Sort { RELEVANCE, LATEST, POPULAR }

    public static final int QUERY_MIN = 2;
    public static final int QUERY_MAX = 20;
    /** FULLTEXT 불리언 검색에서 연산자로 쓰이는 글자. 검색어에서는 빈칸으로 바꾼다. */
    private static final Pattern OPERATORS = Pattern.compile("[+\\-<>()~*\"@]");
    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");

    private final PostCards cards;
    private final NamedParameterJdbcTemplate jdbc;
    private final BoardProperties props;
    private final Clock clock;

    public SearchService(PostCards cards, NamedParameterJdbcTemplate jdbc, BoardProperties props, Clock clock) {
        this.cards = cards;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Result search(String rawQuery, Type type, Sort sort, int page, int size, Long viewerId) {
        String query = normalize(rawQuery);
        String phrase = "\"" + OPERATORS.matcher(query).replaceAll(" ").strip() + "\"";
        String tag = BlogRules.normalizeTag(query);
        Map<Type, Long> counts = Map.of(
                Type.POST, countPosts(postCondition(Type.POST, phrase, tag, query), viewerId),
                Type.BLOG, countBlogs(phrase),
                Type.TAG, tag == null ? 0 : countPosts(postCondition(Type.TAG, phrase, tag, query), viewerId),
                Type.NICKNAME, countPosts(postCondition(Type.NICKNAME, phrase, tag, query), viewerId));
        if (viewerId != null) {
            remember(viewerId, query);
        }
        if (type == Type.BLOG) {
            return new Result(query, type, sort, counts, null, blogs(phrase, sort, page));
        }
        if (type == Type.TAG && tag == null) {
            return new Result(query, type, sort, counts, new PostCards.Page(List.of(), 1, 10, 0, 0), null);
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = PostCards.publicWhere(viewerId, params) + " AND " + postCondition(type, phrase, tag, query).where();
        postCondition(type, phrase, tag, query).params().forEach(params::addValue);
        String order = switch (sort) {
            case LATEST -> "p.created_at DESC, p.id DESC";
            case POPULAR -> "p.like_count DESC, p.created_at DESC, p.id DESC";
            case RELEVANCE -> type == Type.POST
                    ? "MATCH(p.title) AGAINST (:phrase IN BOOLEAN MODE) DESC, p.created_at DESC, p.id DESC"
                    : "p.created_at DESC, p.id DESC";
        };
        return new Result(query, type, sort, counts, cards.page(where, order, params, page, size), null);
    }

    // ----- 최근 검색어 (6.1): 본인만, 최대 10개 -----

    public List<Recent> recent(long memberId) {
        return jdbc.query("""
                SELECT id, keyword FROM recent_searches WHERE member_id = :me ORDER BY searched_at DESC, id DESC LIMIT :max
                """, Map.of("me", memberId, "max", props.recentSearchMax()),
                (rs, i) -> new Recent(rs.getLong("id"), rs.getString("keyword")));
    }

    @Transactional
    public void deleteRecent(long memberId, Long id) {
        if (id == null) {
            jdbc.update("DELETE FROM recent_searches WHERE member_id = :me", Map.of("me", memberId));
        } else {
            jdbc.update("DELETE FROM recent_searches WHERE member_id = :me AND id = :id", Map.of("me", memberId, "id", id));
        }
    }

    private void remember(long memberId, String query) {
        jdbc.update("""
                INSERT INTO recent_searches (member_id, keyword, searched_at) VALUES (:me, :q, :now)
                ON DUPLICATE KEY UPDATE searched_at = VALUES(searched_at)
                """, Map.of("me", memberId, "q", query, "now", LocalDateTime.now(clock)));
        jdbc.update("""
                DELETE FROM recent_searches WHERE member_id = :me AND id NOT IN (
                  SELECT id FROM (SELECT id FROM recent_searches WHERE member_id = :me
                                  ORDER BY searched_at DESC, id DESC LIMIT :max) keep)
                """, Map.of("me", memberId, "max", props.recentSearchMax()));
    }

    // ----- 조건 -----

    /** 2~20자, 글자나 숫자가 하나는 있어야 한다 (US6-1). ngram이 두 글자 단위라 한 글자는 받지 않는다. */
    static String normalize(String raw) {
        String query = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        int length = query.codePointCount(0, query.length());
        if (length < QUERY_MIN || length > QUERY_MAX) {
            throw ApiException.badRequest("INVALID_QUERY", "검색어는 %d~%d자로 넣어 주세요.".formatted(QUERY_MIN, QUERY_MAX));
        }
        String words = OPERATORS.matcher(query).replaceAll(" ").strip();
        if (!HAS_WORD.matcher(words).find() || words.codePointCount(0, words.length()) < QUERY_MIN) {
            throw ApiException.badRequest("INVALID_QUERY", "검색어에 글자나 숫자를 두 자 이상 넣어 주세요.");
        }
        return query;
    }

    private static Condition postCondition(Type type, String phrase, String tag, String query) {
        return switch (type) {
            case TAG -> new Condition("""
                    p.id IN (SELECT pt.post_id FROM post_tags pt JOIN tags t ON t.id = pt.tag_id WHERE t.name = :tag)
                    """, Map.of("tag", tag == null ? "" : tag), false);
            case NICKNAME -> new Condition("m.nickname = :nickname AND m.status = 'ACTIVE' AND p.author_left = FALSE",
                    Map.of("nickname", query), true);
            default -> new Condition("MATCH(p.title) AGAINST (:phrase IN BOOLEAN MODE)", Map.of("phrase", phrase), false);
        };
    }

    private long countPosts(Condition condition, Long viewerId) {
        MapSqlParameterSource params = new MapSqlParameterSource(condition.params());
        // 회원 표는 닉네임 검색에만 붙인다. 건수를 셀 때 글마다 회원을 찾으면 글이 많을수록 느려진다
        String members = condition.needsMember() ? " JOIN members m ON m.id = p.author_id" : "";
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM posts p JOIN blogs b ON b.id = p.blog_id" + members
                + " WHERE " + PostCards.publicWhere(viewerId, params) + " AND " + condition.where(), params, Long.class);
        return n == null ? 0 : n;
    }

    private static final String BLOG_WHERE = """
            b.visibility = 'PUBLIC' AND b.is_hidden = FALSE AND b.status IN ('ACTIVE', 'CLOSING')
            AND MATCH(b.name, b.description) AGAINST (:phrase IN BOOLEAN MODE)
            """;

    private long countBlogs(String phrase) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM blogs b WHERE " + BLOG_WHERE, Map.of("phrase", phrase),
                Long.class);
        return n == null ? 0 : n;
    }

    private BlogPage blogs(String phrase, Sort sort, int page) {
        int size = 12;
        int current = Math.max(page, 1);
        String order = switch (sort) {
            case LATEST -> "b.id DESC";
            case POPULAR -> "subscriber_count DESC, b.id DESC";
            case RELEVANCE -> "MATCH(b.name, b.description) AGAINST (:phrase IN BOOLEAN MODE) DESC, b.id DESC";
        };
        long total = countBlogs(phrase);
        List<BlogCard> items = jdbc.query("""
                SELECT b.slug, b.name, b.description, b.theme_accent, m.nickname,
                       (SELECT i.stored_name FROM images i WHERE i.id = b.cover_image_id) AS cover,
                       (SELECT COUNT(*) FROM subscriptions s WHERE s.blog_id = b.id) AS subscriber_count,
                       (SELECT COUNT(*) FROM blog_memberships x WHERE x.blog_id = b.id AND x.status = 'ACTIVE') AS member_count
                FROM blogs b JOIN members m ON m.id = b.owner_id
                WHERE\s""" + BLOG_WHERE + " ORDER BY " + order + " LIMIT :size OFFSET :offset",
                Map.of("phrase", phrase, "size", size, "offset", (long) (current - 1) * size),
                (rs, i) -> new BlogCard(rs.getString("slug"), rs.getString("name"), rs.getString("description"),
                        rs.getString("theme_accent"), rs.getString("nickname"),
                        rs.getString("cover") == null ? null : "/uploads/" + rs.getString("cover"),
                        rs.getLong("subscriber_count"), rs.getLong("member_count")));
        return new BlogPage(items, current, (int) ((total + size - 1) / size), total);
    }

    private record Condition(String where, Map<String, Object> params, boolean needsMember) {
    }

    public record Result(String query, Type type, Sort sort, Map<Type, Long> counts, PostCards.Page posts,
            BlogPage blogs) {
    }

    public record BlogCard(String slug, String name, String description, String accent, String ownerNickname,
            String coverUrl, long subscriberCount, long memberCount) {
    }

    public record BlogPage(List<BlogCard> items, int page, int totalPages, long total) {
    }

    public record Recent(long id, String keyword) {
    }
}
