package com.blognm.mainblog.report;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.blog.Blog;
import com.blognm.mainblog.blog.BlogService;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.post.PostService;
import com.blognm.mainblog.ratelimit.RateLimiter;
import com.blognm.mainblog.social.SocialProperties;

/**
 * 신고 접수 (SOC-06, D-45, D-85). 처리 화면은 6단계에서 만든다.
 * 블로그 안의 멤버와 그 글·댓글은 블로그장에게, 블로그·블로그장(블로그장의 글·댓글 포함)과
 * 메인 프로필에서 한 회원 신고는 메인 관리자에게 간다. 같은 대상은 한 사람이 2주에 한 번 (SC-004).
 */
@Service
public class ReportService {

    /** 사유 선택지 (추천안 #9). 기타는 내용을 꼭 쓴다. */
    public enum Reason { SPAM, ABUSE, OBSCENE, PRIVACY, COPYRIGHT, ETC }

    public enum Target { MEMBER, BLOG, POST, COMMENT }

    public static final int DETAIL_MAX = 200;

    private final JdbcTemplate jdbc;
    private final BlogService blogs;
    private final PostService posts;
    private final RateLimiter rateLimiter;
    private final SocialProperties props;
    private final Clock clock;

    public ReportService(JdbcTemplate jdbc, BlogService blogs, PostService posts, RateLimiter rateLimiter,
            SocialProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.blogs = blogs;
        this.posts = posts;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
    }

    /** @return 접수처 BLOG_OWNER 또는 ADMIN */
    @Transactional
    public String report(long reporterId, ReportRequest req) {
        if (req.targetType() == null || req.reason() == null) {
            throw ApiException.badRequest("INVALID_REPORT", "신고할 대상과 사유를 골라 주세요.");
        }
        String detail = req.detail() == null ? "" : req.detail().strip();
        if (detail.codePointCount(0, detail.length()) > DETAIL_MAX) {
            throw ApiException.badRequest("INVALID_REPORT", "신고 내용은 %d자까지 쓸 수 있습니다.".formatted(DETAIL_MAX));
        }
        if (req.reason() == Reason.ETC && detail.isEmpty()) {
            throw ApiException.badRequest("INVALID_REPORT", "기타 사유는 내용을 써 주세요.");
        }
        Resolved t = resolve(reporterId, req);
        if (t.authorId() != null && t.authorId() == reporterId) {
            throw ApiException.badRequest("SELF_REPORT", "자기 자신이나 내 글·댓글은 신고할 수 없습니다.");
        }
        if (!rateLimiter.tryAcquire("report:" + reporterId, props.actionsPerMinute(), Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "신고를 너무 자주 했습니다. 잠시 뒤에 다시 해 주세요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 두 번 눌러도 신고가 두 개 생기지 않게 신고자 행을 잠근다.
        jdbc.queryForList("SELECT id FROM members WHERE id = ? FOR UPDATE", Long.class, reporterId);
        Integer recent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reports
                WHERE reporter_id = ? AND target_type = ? AND target_id = ? AND created_at > ?
                """, Integer.class, reporterId, t.type().name(), t.id(), now.minus(props.reportCooldown()));
        if (recent != null && recent > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REPORTED",
                    "이미 신고한 대상입니다. 같은 대상은 %d일에 한 번 신고할 수 있습니다.".formatted(props.reportCooldown().toDays()));
        }
        jdbc.update("""
                INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler, reason_code, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, reporterId, t.type().name(), t.id(), t.blogId(), t.handler(), req.reason().name(),
                detail.isEmpty() ? null : detail, now);
        return t.handler();
    }

    private Resolved resolve(long reporterId, ReportRequest req) {
        return switch (req.targetType()) {
            case POST -> {
                PostService.Readable p = posts.readable(requireId(req), req.key(), reporterId);
                yield inBlog(Target.POST, p.post().getId(), p.blog(), p.post().getAuthorId());
            }
            case COMMENT -> {
                List<Long[]> rows = jdbc.query("""
                        SELECT c.post_id, c.author_id FROM comments c WHERE c.id = ? AND c.status = 'NORMAL'
                        """, (rs, i) -> new Long[] { rs.getLong("post_id"), rs.getLong("author_id") }, requireId(req));
                if (rows.isEmpty()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다. 이미 지워졌을 수 있습니다.");
                }
                PostService.Readable p = posts.readable(rows.get(0)[0], req.key(), reporterId);
                yield inBlog(Target.COMMENT, requireId(req), p.blog(), rows.get(0)[1]);
            }
            case BLOG -> {
                Blog blog = blogs.visibleBlog(req.slug(), req.key(), reporterId);
                if (blog.getOwnerId() == reporterId) {
                    throw ApiException.badRequest("SELF_REPORT", "내가 운영하는 블로그는 신고할 수 없습니다.");
                }
                yield new Resolved(Target.BLOG, blog.getId(), blog.getId(), "ADMIN", null);
            }
            case MEMBER -> {
                long memberId = requireId(req);
                Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE id = ? AND status = 'ACTIVE'",
                        Integer.class, memberId);
                if (active == null || active == 0) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "회원을 찾을 수 없습니다.");
                }
                if (req.slug() == null || req.slug().isBlank()) {
                    // 메인 프로필에서 한 신고 (D-85)
                    yield new Resolved(Target.MEMBER, memberId, null, "ADMIN", memberId);
                }
                Blog blog = blogs.visibleBlog(req.slug(), req.key(), reporterId);
                Integer member = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM blog_memberships WHERE blog_id = ? AND member_id = ? AND status = 'ACTIVE'
                        """, Integer.class, blog.getId(), memberId);
                if (member == null || member == 0) {
                    yield new Resolved(Target.MEMBER, memberId, null, "ADMIN", memberId);
                }
                yield inBlog(Target.MEMBER, memberId, blog, memberId);
            }
        };
    }

    /** 블로그 안의 일. 블로그장 본인이 대상이면 메인 관리자에게 (D-85). */
    private static Resolved inBlog(Target type, long id, Blog blog, long authorId) {
        String handler = authorId == blog.getOwnerId() ? "ADMIN" : "BLOG_OWNER";
        return new Resolved(type, id, blog.getId(), handler, authorId);
    }

    private static long requireId(ReportRequest req) {
        if (req.targetId() == null) {
            throw ApiException.badRequest("INVALID_REPORT", "신고할 대상을 골라 주세요.");
        }
        return req.targetId();
    }

    private record Resolved(Target type, long id, Long blogId, String handler, Long authorId) {
    }

    /** targetId: 회원·글·댓글 번호. slug: 블로그 신고, 또는 블로그 안에서 회원을 신고할 때 그 블로그. */
    public record ReportRequest(Target targetType, Long targetId, String slug, String key, Reason reason,
            String detail) {
    }
}
