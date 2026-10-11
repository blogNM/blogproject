package com.blognm.mainblog.social;

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

import com.blognm.mainblog.board.PostCards;
import com.blognm.mainblog.common.ApiException;

/**
 * 프로필(SOC-03)과 [내 활동](SOC-07, D-91). 다른 사람에게는 닉네임·사진·소개·운영·참여 블로그·팔로워 수만 보이고
 * 이메일·이름·전화번호는 응답에도 넣지 않는다 (4.4, SC-003).
 * 쓴 글·쓴 댓글(D-94)·좋아요 한 글·팔로잉·구독 목록은 본인이 "다른 사람에게 보여주기"를 켜 둔 것만 남이 본다 (기본 켜짐).
 * 남에게는 그 사람이 볼 수 있는 글·블로그만 나온다.
 */
@Service
public class ProfileService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final int LIST_SIZE = 20;

    public enum ActivityList { POSTS, COMMENTS, LIKES, FOLLOWING, SUBSCRIPTIONS }

    private final NamedParameterJdbcTemplate jdbc;
    private final PostCards cards;
    private final FollowService follows;
    private final BlockService blocks;
    private final SocialProperties props;
    private final Clock clock;

    public ProfileService(NamedParameterJdbcTemplate jdbc, PostCards cards, FollowService follows, BlockService blocks,
            SocialProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.follows = follows;
        this.blocks = blocks;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Profile profile(long memberId, Long viewerId) {
        Row m = member(memberId);
        boolean self = viewerId != null && viewerId == memberId;
        Visibility visibility = new Visibility(m.showPosts(), m.showComments(), m.showLikes(), m.showFollowing(),
                m.showSubscriptions());
        return new Profile(m.id(), m.nickname(), m.bio(), m.imageUrl(), m.joinedAt(), self,
                follows.following(viewerId, memberId),
                viewerId != null && !self && blocks.blockedBy(viewerId, memberId),
                count("SELECT COUNT(*) FROM follows f JOIN members x ON x.id = f.follower_id "
                        + "WHERE f.followee_id = :id AND x.status = 'ACTIVE'", memberId),
                count("SELECT COUNT(*) FROM follows f JOIN members x ON x.id = f.followee_id "
                        + "WHERE f.follower_id = :id AND x.status = 'ACTIVE'", memberId),
                blogs(memberId, viewerId, true), blogs(memberId, viewerId, false),
                self ? new Visibility(true, true, true, true, true) : visibility, self ? visibility : null);
    }

    /** 내가 쓴 글·좋아요 한 글. 보는 사람이 볼 수 있는 글만. */
    @Transactional(readOnly = true)
    public PostCards.Page posts(long memberId, Long viewerId, ActivityList list, int page) {
        Row m = member(memberId);
        requireVisible(m, viewerId, list);
        MapSqlParameterSource params = new MapSqlParameterSource("id", memberId);
        String where = readableWhere(viewerId, params);
        if (list == ActivityList.POSTS) {
            return cards.page(where + " AND p.author_id = :id AND p.author_left = FALSE",
                    "p.created_at DESC, p.id DESC", params, page, LIST_SIZE);
        }
        return cards.page(where + " AND p.id IN (SELECT l.post_id FROM post_likes l WHERE l.member_id = :id)",
                "(SELECT l.created_at FROM post_likes l WHERE l.member_id = :id AND l.post_id = p.id) DESC, p.id DESC",
                params, page, LIST_SIZE);
    }

    /**
     * 쓴 댓글 (D-94). 보는 사람이 읽을 수 있는 글에 달린, 지워지거나 숨겨지지 않은 댓글만 최근 순으로.
     * 누르면 그 글의 댓글 자리로 간다.
     */
    @Transactional(readOnly = true)
    public CommentPage comments(long memberId, Long viewerId, int page) {
        Row m = member(memberId);
        requireVisible(m, viewerId, ActivityList.COMMENTS);
        MapSqlParameterSource params = new MapSqlParameterSource("id", memberId);
        String from = " FROM comments c JOIN posts p ON p.id = c.post_id JOIN blogs b ON b.id = p.blog_id"
                + " WHERE c.author_id = :id AND c.status = 'NORMAL' AND " + readableWhere(viewerId, params);
        if (viewerId != null) {
            from += " AND NOT EXISTS (SELECT 1 FROM blocks bk2 WHERE (bk2.blocker_id = :viewerId AND bk2.blocked_id = :id)"
                    + " OR (bk2.blocker_id = :id AND bk2.blocked_id = :viewerId))";
        }
        long total = count("SELECT COUNT(*)" + from, params);
        int current = Math.max(page, 1);
        params.addValue("size", LIST_SIZE).addValue("offset", (current - 1) * LIST_SIZE);
        List<CommentCard> items = jdbc.query("""
                SELECT c.id, c.content, c.is_edited, c.created_at, c.parent_id, p.id AS post_id, p.title,
                       b.slug, b.name AS blog_name
                """ + from + " ORDER BY c.created_at DESC, c.id DESC LIMIT :size OFFSET :offset", params,
                (rs, i) -> new CommentCard(rs.getLong("id"), rs.getString("content"), rs.getBoolean("is_edited"),
                        rs.getObject("parent_id") != null,
                        rs.getObject("created_at", LocalDateTime.class).format(DATE_TIME), rs.getLong("post_id"),
                        rs.getString("title"), rs.getString("slug"), rs.getString("blog_name")));
        return new CommentPage(items, current, pages(total), total);
    }

    /** 팔로워(누구나 볼 수 있음, SOC-02)와 팔로잉(공개 설정을 따름). 탈퇴한 회원은 빠진다. */
    @Transactional(readOnly = true)
    public MemberPage people(long memberId, Long viewerId, boolean followers, int page) {
        Row m = member(memberId);
        if (!followers) {
            requireVisible(m, viewerId, ActivityList.FOLLOWING);
        }
        String join = followers ? "f.follower_id" : "f.followee_id";
        String side = followers ? "f.followee_id" : "f.follower_id";
        String from = " FROM follows f JOIN members x ON x.id = " + join + " WHERE " + side + " = :id AND x.status = 'ACTIVE'";
        long total = count("SELECT COUNT(*)" + from, memberId);
        int current = Math.max(page, 1);
        List<MemberCard> items = jdbc.query("""
                SELECT x.id, x.nickname, x.bio, (SELECT i.stored_name FROM images i WHERE i.id = x.profile_image_id) AS image
                """ + from + " ORDER BY f.created_at DESC LIMIT :size OFFSET :offset",
                Map.of("id", memberId, "size", LIST_SIZE, "offset", (current - 1) * LIST_SIZE),
                (rs, i) -> new MemberCard(rs.getLong("id"), rs.getString("nickname"), rs.getString("bio"),
                        image(rs.getString("image"))));
        return new MemberPage(items, current, pages(total), total);
    }

    /** 구독한 블로그. 남에게는 공개 블로그만 보인다. */
    @Transactional(readOnly = true)
    public BlogPage subscriptions(long memberId, Long viewerId, int page) {
        Row m = member(memberId);
        requireVisible(m, viewerId, ActivityList.SUBSCRIPTIONS);
        MapSqlParameterSource params = new MapSqlParameterSource("id", memberId);
        String from = " FROM subscriptions s JOIN blogs b ON b.id = s.blog_id WHERE s.member_id = :id AND "
                + blogVisibleWhere(viewerId, params);
        long total = count("SELECT COUNT(*)" + from, params);
        int current = Math.max(page, 1);
        params.addValue("size", LIST_SIZE).addValue("offset", (current - 1) * LIST_SIZE);
        List<BlogCard> items = jdbc.query(BLOG_COLUMNS + ", NULL AS role" + from
                + " ORDER BY s.created_at DESC LIMIT :size OFFSET :offset", params, ProfileService::blogCard);
        return new BlogPage(items, current, pages(total), total);
    }

    // ----- 내 프로필 꾸미기 -----

    @Transactional
    public void updateBio(long memberId, String raw) {
        String bio = raw == null ? "" : raw.strip();
        if (bio.codePointCount(0, bio.length()) > props.bioMax()) {
            throw ApiException.badRequest("INVALID_BIO", "소개는 %d자까지 쓸 수 있습니다.".formatted(props.bioMax()));
        }
        jdbc.update("UPDATE members SET bio = :bio WHERE id = :id",
                new MapSqlParameterSource("bio", bio.isEmpty() ? null : bio).addValue("id", memberId));
    }

    @Transactional
    public String changeImage(long memberId, Long imageId) {
        jdbc.update("UPDATE members SET profile_image_id = :img WHERE id = :id",
                new MapSqlParameterSource("img", imageId).addValue("id", memberId));
        return member(memberId).imageUrl();
    }

    @Transactional
    public void updateVisibility(long memberId, Visibility v) {
        jdbc.update("""
                UPDATE members SET show_posts = :posts, show_comments = COALESCE(:comments, show_comments),
                                   show_likes = :likes, show_following = :following, show_subscriptions = :subs
                WHERE id = :id
                """, new MapSqlParameterSource("posts", v.posts()).addValue("comments", v.comments())
                .addValue("likes", v.likes())
                .addValue("following", v.following()).addValue("subs", v.subscriptions()).addValue("id", memberId));
    }

    /** 내가 차단한 회원. 내 정보에서 풀 수 있다. */
    @Transactional(readOnly = true)
    public List<MemberCard> blocked(long memberId) {
        return jdbc.query("""
                SELECT x.id, x.nickname, x.bio, (SELECT i.stored_name FROM images i WHERE i.id = x.profile_image_id) AS image
                FROM blocks k JOIN members x ON x.id = k.blocked_id
                WHERE k.blocker_id = :id AND x.status = 'ACTIVE' ORDER BY k.created_at DESC
                """, Map.of("id", memberId), (rs, i) -> new MemberCard(rs.getLong("id"), rs.getString("nickname"),
                rs.getString("bio"), image(rs.getString("image"))));
    }

    // ----- 내부 -----

    /** 이 사람이 볼 수 있는 글: 공개 블로그, 또는 내가 멤버인 블로그. 서로 차단한 사이의 글은 뺀다. */
    static String readableWhere(Long viewerId, MapSqlParameterSource params) {
        if (viewerId == null) {
            return PostCards.publicWhere(null, params);
        }
        params.addValue("viewerId", viewerId);
        return """
                p.status = 'NORMAL' AND b.is_hidden = FALSE AND b.status IN ('ACTIVE', 'CLOSING')
                AND (b.visibility = 'PUBLIC' OR EXISTS (SELECT 1 FROM blog_memberships bm
                     WHERE bm.blog_id = b.id AND bm.member_id = :viewerId AND bm.status = 'ACTIVE'))
                AND NOT EXISTS (SELECT 1 FROM blocks bk
                                WHERE (bk.blocker_id = :viewerId AND bk.blocked_id = p.author_id)
                                   OR (bk.blocker_id = p.author_id AND bk.blocked_id = :viewerId))
                """;
    }

    private static String blogVisibleWhere(Long viewerId, MapSqlParameterSource params) {
        String base = "b.is_hidden = FALSE AND b.status IN ('ACTIVE', 'CLOSING') AND ";
        if (viewerId == null) {
            return base + "b.visibility = 'PUBLIC'";
        }
        params.addValue("viewerId", viewerId);
        return base + """
                (b.visibility = 'PUBLIC' OR EXISTS (SELECT 1 FROM blog_memberships bm
                 WHERE bm.blog_id = b.id AND bm.member_id = :viewerId AND bm.status = 'ACTIVE'))
                """;
    }

    private static final String BLOG_COLUMNS = """
            SELECT b.slug, b.name, b.description, b.theme_accent,
                   (SELECT i.stored_name FROM images i WHERE i.id = b.cover_image_id) AS cover
            """;

    /** 운영하는 블로그(owned)와 참여한 블로그. 보는 사람이 볼 수 있는 블로그만. */
    private List<BlogCard> blogs(long memberId, Long viewerId, boolean owned) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", memberId);
        String role = owned ? "bm2.role = 'OWNER'" : "bm2.role <> 'OWNER'";
        return jdbc.query(BLOG_COLUMNS + ", bm2.role AS role FROM blog_memberships bm2 JOIN blogs b ON b.id = bm2.blog_id "
                + "WHERE bm2.member_id = :id AND bm2.status = 'ACTIVE' AND " + role + " AND "
                + blogVisibleWhere(viewerId, params) + " ORDER BY bm2.joined_at", params, ProfileService::blogCard);
    }

    private static BlogCard blogCard(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new BlogCard(rs.getString("slug"), rs.getString("name"), rs.getString("description"),
                rs.getString("theme_accent"), image(rs.getString("cover")), rs.getString("role"));
    }

    private void requireVisible(Row m, Long viewerId, ActivityList list) {
        if (viewerId != null && viewerId == m.id()) {
            return;
        }
        boolean open = switch (list) {
            case POSTS -> m.showPosts();
            case COMMENTS -> m.showComments();
            case LIKES -> m.showLikes();
            case FOLLOWING -> m.showFollowing();
            case SUBSCRIPTIONS -> m.showSubscriptions();
        };
        if (!open) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRIVATE_LIST", "이 회원이 다른 사람에게 보여주지 않는 목록입니다.");
        }
    }

    private Row member(long memberId) {
        return jdbc.query("""
                SELECT m.id, m.nickname, m.bio, m.created_at, m.show_posts, m.show_comments, m.show_likes, m.show_following,
                       m.show_subscriptions, (SELECT i.stored_name FROM images i WHERE i.id = m.profile_image_id) AS image
                FROM members m WHERE m.id = :id AND m.status = 'ACTIVE'
                """, Map.of("id", memberId), (rs, i) -> new Row(rs.getLong("id"), rs.getString("nickname"),
                rs.getString("bio"), image(rs.getString("image")),
                rs.getObject("created_at", LocalDateTime.class).format(DATE), rs.getBoolean("show_posts"),
                rs.getBoolean("show_comments"), rs.getBoolean("show_likes"), rs.getBoolean("show_following"), rs.getBoolean("show_subscriptions")))
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "회원을 찾을 수 없습니다."));
    }

    private static String image(String storedName) {
        return storedName == null ? null : "/uploads/" + storedName;
    }

    private long count(String sql, long id) {
        return count(sql, new MapSqlParameterSource("id", id));
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long n = jdbc.queryForObject(sql, params, Long.class);
        return n == null ? 0 : n;
    }

    private static int pages(long total) {
        return (int) ((total + LIST_SIZE - 1) / LIST_SIZE);
    }

    private record Row(long id, String nickname, String bio, String imageUrl, String joinedAt, boolean showPosts,
            boolean showComments, boolean showLikes, boolean showFollowing, boolean showSubscriptions) {
    }

    /** visible: 보는 사람이 열 수 있는 목록. mySettings: 본인에게만, 다른 사람에게 보여주기 체크 값. */
    public record Profile(long id, String nickname, String bio, String imageUrl, String joinedAt, boolean me,
            boolean followedByMe, boolean blockedByMe, long followers, long following, List<BlogCard> ownedBlogs,
            List<BlogCard> joinedBlogs, Visibility visible, Visibility mySettings) {
    }

    /** comments를 빼고 보내면 그 설정은 그대로 둔다 (D-94 전에 만든 화면·스크립트). */
    public record Visibility(boolean posts, Boolean comments, boolean likes, boolean following, boolean subscriptions) {
    }

    public record CommentCard(long id, String content, boolean edited, boolean reply, String createdAt, long postId,
            String postTitle, String blogSlug, String blogName) {
    }

    public record CommentPage(List<CommentCard> items, int page, int totalPages, long total) {
    }

    public record MemberCard(long id, String nickname, String bio, String imageUrl) {
    }

    public record MemberPage(List<MemberCard> items, int page, int totalPages, long total) {
    }

    public record BlogCard(String slug, String name, String description, String accent, String coverUrl, String role) {
    }

    public record BlogPage(List<BlogCard> items, int page, int totalPages, long total) {
    }
}
