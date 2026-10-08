package com.blognm.mainblog.post;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.board.BoardProperties;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.config.AppProperties;
import com.blognm.mainblog.notification.NotificationWriter;
import com.blognm.mainblog.ratelimit.RateLimiter;

/** 좋아요(BRD-06)와 조회수(BRD-11). */
@Service
public class ReactionService {

    private final JdbcTemplate jdbc;
    private final PostService posts;
    private final NotificationWriter notifications;
    private final RateLimiter rateLimiter;
    private final BoardProperties props;
    private final AppProperties app;
    private final Clock clock;

    public ReactionService(JdbcTemplate jdbc, PostService posts, NotificationWriter notifications,
            RateLimiter rateLimiter, BoardProperties props, AppProperties app, Clock clock) {
        this.jdbc = jdbc;
        this.posts = posts;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.app = app;
        this.clock = clock;
    }

    /** 누르면 좋아요, 한 번 더 누르면 취소 (US5-4). 회원당 1분에 30번까지 (추천안 #11). */
    @Transactional
    public LikeResult toggleLike(long postId, String key, long actorId) {
        PostService.Readable post = posts.readable(postId, key, actorId);
        if (!rateLimiter.tryAcquire("like:" + actorId, props.reactionsPerMinute(), Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "좋아요를 너무 자주 눌렀습니다. 잠시 뒤에 다시 해 주세요.");
        }
        boolean liked;
        if (jdbc.update("DELETE FROM post_likes WHERE member_id = ? AND post_id = ?", actorId, postId) == 1) {
            jdbc.update("UPDATE posts SET like_count = GREATEST(like_count - 1, 0) WHERE id = ?", postId);
            liked = false;
        } else {
            jdbc.update("INSERT IGNORE INTO post_likes (member_id, post_id, created_at) VALUES (?, ?, ?)", actorId,
                    postId, LocalDateTime.now(clock));
            jdbc.update("UPDATE posts SET like_count = like_count + 1 WHERE id = ?", postId);
            liked = true;
            long author = post.post().getAuthorId();
            if (author != actorId && !post.post().isAuthorLeft()) {
                notifications.like(author, post.blog().getId(), actorId, postId);
            }
        }
        Integer count = jdbc.queryForObject("SELECT like_count FROM posts WHERE id = ?", Integer.class, postId);
        return new LikeResult(liked, count == null ? 0 : count);
    }

    @Transactional(readOnly = true)
    public boolean liked(long postId, Long memberId) {
        if (memberId == null) {
            return false;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM post_likes WHERE member_id = ? AND post_id = ?",
                Integer.class, memberId, postId);
        return n != null && n > 0;
    }

    /**
     * 같은 사람이 같은 날 같은 글을 보면 한 번만 센다 (D-77). 회원은 회원 번호, 비회원은 IP로 본다 (추천안 #13).
     * IP는 원문 대신 해시로 남긴다. 블로그장이 자기 블로그 글을 봐도 센다.
     */
    @Transactional
    public void recordView(long postId, Long memberId, String ip) {
        String viewer = memberId != null ? "m:" + memberId
                : "ip:" + Hashing.hmacSha256Hex(app.auth().codeHashSecret(), "VIEW:" + ip).substring(0, 40);
        LocalDate today = LocalDate.now(clock);
        int inserted = jdbc.update("INSERT IGNORE INTO post_views (post_id, viewer_key, view_date) VALUES (?, ?, ?)",
                postId, viewer, today);
        if (inserted == 1) {
            jdbc.update("UPDATE posts SET view_count = view_count + 1 WHERE id = ?", postId);
        }
    }

    public record LikeResult(boolean liked, int likes) {
    }
}
