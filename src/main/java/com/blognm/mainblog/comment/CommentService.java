package com.blognm.mainblog.comment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.board.BoardProperties;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.notification.NotificationWriter;
import com.blognm.mainblog.post.PostService;
import com.blognm.mainblog.ratelimit.RateLimiter;
import com.blognm.mainblog.social.BlockService;

/**
 * 댓글·대댓글 (BRD-06, 004 US5). 대댓글은 1단계까지이고, 답글에 다시 답하면 같은 줄기에 붙고 앞에 @닉네임이 붙는다 (D-78).
 * 답글이 남아 있는 댓글을 지우면 "삭제된 댓글입니다"로 남긴다. 수정한 댓글은 "수정됨"을 표시한다.
 */
@Service
public class CommentService {

    public static final int CONTENT_MAX = 500;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private final JdbcTemplate jdbc;
    private final PostService posts;
    private final NotificationWriter notifications;
    private final RateLimiter rateLimiter;
    private final BoardProperties props;
    private final BlockService blocks;
    private final Clock clock;

    public CommentService(JdbcTemplate jdbc, PostService posts, NotificationWriter notifications,
            RateLimiter rateLimiter, BoardProperties props, BlockService blocks, Clock clock) {
        this.jdbc = jdbc;
        this.posts = posts;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.blocks = blocks;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CommentView> list(long postId, String key, Long viewerId) {
        PostService.Readable post = posts.readable(postId, key, viewerId);
        List<Row> rows = jdbc.query("""
                SELECT c.id, c.post_id, c.parent_id, c.author_id, c.content, c.is_edited, c.status, c.created_at,
                       m.nickname, m.status AS member_status, r.nickname AS reply_to
                FROM comments c
                JOIN members m ON m.id = c.author_id
                LEFT JOIN members r ON r.id = c.reply_to_member_id
                WHERE c.post_id = ?
                ORDER BY c.created_at, c.id
                """, CommentService::row, postId);
        // 서로 차단한 사이의 댓글은 숨긴다. 같은 블로그의 멤버끼리는 보인다 (D-36).
        Set<Long> hidden = viewerId == null ? Set.of() : blocks.hiddenIn(post.blog().getId(), viewerId);
        Map<Long, CommentView> roots = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.parentId() == null) {
                Row shown = hidden.contains(r.authorId()) ? r.asDeleted() : r;
                roots.put(r.id(), view(shown, viewerId, post, new ArrayList<>()));
            }
        }
        for (Row r : rows) {
            if (r.parentId() != null && r.normal() && roots.containsKey(r.parentId()) && !hidden.contains(r.authorId())) {
                roots.get(r.parentId()).replies().add(view(r, viewerId, post, List.of()));
            }
        }
        return roots.values().stream().filter(c -> !c.deleted() || !c.replies().isEmpty()).toList();
    }

    /** 글을 읽을 수 있는 회원은 누구나 댓글을 단다. 답글에 다시 답하면 그 댓글의 줄기에 붙는다 (D-78). */
    @Transactional
    public long write(long postId, String key, long actorId, WriteRequest req) {
        PostService.Readable post = posts.readable(postId, key, actorId);
        String content = normalize(req.content());
        long blogOfPost = post.blog().getId();
        blocks.requireNotBlocked(blogOfPost, actorId, post.post().getAuthorId(), "댓글을 달");
        if (!rateLimiter.tryAcquire("comment:" + actorId, props.commentsPerMinute(), Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "댓글을 너무 빨리 달고 있습니다. 잠시 뒤에 다시 해 주세요.");
        }
        Long parentId = null;
        Long replyTo = null;
        if (req.parentId() != null) {
            Row target = find(req.parentId())
                    .filter(r -> r.postId() == postId && r.normal())
                    .orElseThrow(() -> ApiException.badRequest("PARENT_NOT_FOUND", "답글을 달 댓글이 지워졌습니다."));
            parentId = target.parentId() == null ? target.id() : target.parentId();
            replyTo = target.authorId();
            blocks.requireNotBlocked(blogOfPost, actorId, replyTo, "답글을 달");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        Long parent = parentId;
        Long replyMember = replyTo;
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO comments (post_id, author_id, parent_id, reply_to_member_id, content, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[] { "id" });
            ps.setLong(1, postId);
            ps.setLong(2, actorId);
            ps.setObject(3, parent);
            ps.setObject(4, replyMember);
            ps.setString(5, content);
            ps.setObject(6, now);
            ps.setObject(7, now);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        jdbc.update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ?", postId);

        long blogId = post.blog().getId();
        long postAuthor = post.post().getAuthorId();
        if (postAuthor != actorId && !post.post().isAuthorLeft()) {
            notifications.comment(postAuthor, NotificationWriter.Type.COMMENT, blogId, actorId, id);
        }
        if (replyTo != null && replyTo != actorId && replyTo != postAuthor) {
            notifications.comment(replyTo, NotificationWriter.Type.REPLY, blogId, actorId, id);
        }
        return id;
    }

    /** 쓴 사람만 고친다. 고치면 "수정됨"이 붙는다. */
    @Transactional
    public void update(long commentId, String key, long actorId, String rawContent) {
        Row row = find(commentId).filter(Row::normal).orElseThrow(CommentService::notFound);
        posts.readable(row.postId(), key, actorId);
        if (row.authorId() != actorId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_AUTHOR", "댓글은 쓴 사람만 고칠 수 있습니다.");
        }
        jdbc.update("UPDATE comments SET content = ?, is_edited = TRUE, updated_at = ? WHERE id = ?",
                normalize(rawContent), LocalDateTime.now(clock), commentId);
    }

    /** 쓴 사람, 또는 그 블로그의 블로그장(글 관리 권한이 있는 부블로그장 포함)이 지운다. */
    @Transactional
    public void delete(long commentId, String key, long actorId) {
        Row row = find(commentId).filter(Row::normal).orElseThrow(CommentService::notFound);
        PostService.Readable post = posts.readable(row.postId(), key, actorId);
        if (row.authorId() != actorId && !post.canManagePosts()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 댓글을 지울 권한이 없습니다.");
        }
        jdbc.update("UPDATE comments SET status = 'DELETED', updated_at = ? WHERE id = ?",
                LocalDateTime.now(clock), commentId);
        jdbc.update("UPDATE posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = ?", row.postId());
    }

    private static String normalize(String raw) {
        String content = raw == null ? "" : raw.strip();
        int length = content.codePointCount(0, content.length());
        if (length < 1 || length > CONTENT_MAX) {
            throw ApiException.badRequest("INVALID_COMMENT", "댓글은 1~%d자로 써 주세요.".formatted(CONTENT_MAX));
        }
        return content;
    }

    private Optional<Row> find(long id) {
        return jdbc.query("""
                SELECT c.id, c.post_id, c.parent_id, c.author_id, c.content, c.is_edited, c.status, c.created_at,
                       m.nickname, m.status AS member_status, NULL AS reply_to
                FROM comments c JOIN members m ON m.id = c.author_id WHERE c.id = ?
                """, CommentService::row, id).stream().findFirst();
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getLong("id"), rs.getLong("post_id"), rs.getObject("parent_id", Long.class), rs.getLong("author_id"), rs.getString("content"),
                rs.getBoolean("is_edited"), "NORMAL".equals(rs.getString("status")),
                rs.getObject("created_at", LocalDateTime.class),
                "ACTIVE".equals(rs.getString("member_status")) ? rs.getString("nickname") : "탈퇴한 회원",
                rs.getString("reply_to"), "ACTIVE".equals(rs.getString("member_status")));
    }

    private static CommentView view(Row r, Long viewerId, PostService.Readable post, List<CommentView> replies) {
        boolean mine = viewerId != null && r.authorId() == viewerId;
        if (!r.normal()) {
            return new CommentView(r.id(), null, null, null, "삭제된 댓글입니다.", r.createdAt().format(DATE_TIME), false,
                    true, false, false, replies);
        }
        return new CommentView(r.id(), r.authorActive() ? r.authorId() : null, r.authorName(), r.replyTo(), r.content(),
                r.createdAt().format(DATE_TIME), r.edited(), false, mine, mine || post.canManagePosts(), replies);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다. 이미 지워졌을 수 있습니다.");
    }

    private record Row(long id, long postId, Long parentId, long authorId, String content, boolean edited,
            boolean normal, LocalDateTime createdAt, String authorName, String replyTo, boolean authorActive) {

        /** 차단해서 숨긴 댓글은 지운 댓글처럼 보인다. 답글이 없으면 목록에서 빠진다. */
        Row asDeleted() {
            return new Row(id, postId, parentId, authorId, null, false, false, createdAt, null, null, false);
        }
    }

    public record WriteRequest(String content, Long parentId) {
    }

    /** 화면은 모든 값을 textContent로 넣는다 (헌법 III). authorId는 프로필 링크용, 탈퇴한 회원이면 null. */
    public record CommentView(long id, Long authorId, String authorName, String replyTo, String content, String createdAt,
            boolean edited, boolean deleted, boolean mine, boolean canDelete, List<CommentView> replies) {
    }
}
