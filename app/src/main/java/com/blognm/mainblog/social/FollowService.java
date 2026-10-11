package com.blognm.mainblog.social;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.blog.Blog;
import com.blognm.mainblog.blog.BlogService;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.notification.NotificationWriter;
import com.blognm.mainblog.ratelimit.RateLimiter;

/** 회원 팔로우와 블로그 구독 (SOC-01, D-02). 누르면 하고, 한 번 더 누르면 취소한다 (US2-1). */
@Service
public class FollowService {

    private final JdbcTemplate jdbc;
    private final BlogService blogs;
    private final BlockService blocks;
    private final NotificationWriter notifications;
    private final RateLimiter rateLimiter;
    private final SocialProperties props;
    private final Clock clock;

    public FollowService(JdbcTemplate jdbc, BlogService blogs, BlockService blocks, NotificationWriter notifications,
            RateLimiter rateLimiter, SocialProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.blogs = blogs;
        this.blocks = blocks;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Toggle follow(long actorId, long targetId) {
        if (actorId == targetId) {
            throw ApiException.badRequest("SELF_FOLLOW", "자기 자신은 팔로우할 수 없습니다.");
        }
        requireActive(targetId);
        limit("follow", actorId, "팔로우");
        boolean on;
        if (jdbc.update("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?", actorId, targetId) == 1) {
            on = false;
        } else {
            if (blocks.between(actorId, targetId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "차단한 사이에는 팔로우할 수 없습니다.");
            }
            jdbc.update("INSERT IGNORE INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, ?)", actorId,
                    targetId, LocalDateTime.now(clock));
            notifications.onceADay(targetId, NotificationWriter.Type.NEW_FOLLOWER, null, actorId, "MEMBER", actorId);
            on = true;
        }
        return new Toggle(on, count("SELECT COUNT(*) FROM follows f JOIN members m ON m.id = f.follower_id "
                + "WHERE f.followee_id = ? AND m.status = 'ACTIVE'", targetId));
    }

    /** 볼 수 있는 블로그만 구독한다. 블로그장에게 알림이 간다 (US2-3). */
    @Transactional
    public Toggle subscribe(long actorId, String slug, String key) {
        Blog blog = blogs.visibleBlog(slug, key, actorId);
        limit("subscribe", actorId, "구독");
        boolean on;
        if (jdbc.update("DELETE FROM subscriptions WHERE member_id = ? AND blog_id = ?", actorId, blog.getId()) == 1) {
            on = false;
        } else {
            if (blog.isClosed()) {
                throw new ApiException(HttpStatus.CONFLICT, "BLOG_CLOSED", "폐쇄된 블로그는 구독할 수 없습니다.");
            }
            jdbc.update("INSERT IGNORE INTO subscriptions (member_id, blog_id, created_at) VALUES (?, ?, ?)", actorId,
                    blog.getId(), LocalDateTime.now(clock));
            if (blog.getOwnerId() != actorId) {
                notifications.onceADay(blog.getOwnerId(), NotificationWriter.Type.BLOG_SUBSCRIBED, blog.getId(), actorId,
                        "BLOG", blog.getId());
            }
            on = true;
        }
        return new Toggle(on, count("SELECT COUNT(*) FROM subscriptions WHERE blog_id = ?", blog.getId()));
    }

    @Transactional(readOnly = true)
    public boolean following(Long actorId, long targetId) {
        return actorId != null && count("SELECT COUNT(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
                actorId, targetId) > 0;
    }

    @Transactional(readOnly = true)
    public boolean subscribed(Long actorId, long blogId) {
        return actorId != null && count("SELECT COUNT(*) FROM subscriptions WHERE member_id = ? AND blog_id = ?",
                actorId, blogId) > 0;
    }

    @Transactional(readOnly = true)
    public long subscribers(long blogId) {
        return count("SELECT COUNT(*) FROM subscriptions WHERE blog_id = ?", blogId);
    }

    /** 팔로우·구독·차단은 종류별로 회원당 1분에 30번까지 (추천안 #11). */
    void limit(String kind, long actorId, String label) {
        if (!rateLimiter.tryAcquire(kind + ":" + actorId, props.actionsPerMinute(), Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "%s를 너무 자주 눌렀습니다. 잠시 뒤에 다시 해 주세요.".formatted(label));
        }
    }

    void requireActive(long memberId) {
        if (count("SELECT COUNT(*) FROM members WHERE id = ? AND status = 'ACTIVE'", memberId) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "회원을 찾을 수 없습니다.");
        }
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    public record Toggle(boolean on, long count) {
    }
}
