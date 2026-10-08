package com.blognm.mainblog.social;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.common.ApiException;

/**
 * 차단 (SOC-05, D-36). 한쪽이 차단해도 양쪽으로 본다 (추천안 #10): 서로의 글·댓글이 서로에게 숨겨지고,
 * 서로 팔로우·댓글·좋아요를 할 수 없다. 같은 블로그의 멤버끼리는 그 블로그 안에서 보인다.
 */
@Service
public class BlockService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BlockService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 둘 중 누구든 상대를 차단했는지. */
    @Transactional(readOnly = true)
    public boolean between(long a, long b) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM blocks
                WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)
                """, Integer.class, a, b, b, a);
        return n != null && n > 0;
    }

    @Transactional(readOnly = true)
    public boolean blockedBy(long blocker, long blocked) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                Integer.class, blocker, blocked);
        return n != null && n > 0;
    }

    /** 둘 다 이 블로그의 멤버인지. 같은 블로그 안의 일은 차단과 상관없이 보인다 (D-36). */
    @Transactional(readOnly = true)
    public boolean sameBlog(long blogId, long a, long b) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM blog_memberships WHERE blog_id = ? AND member_id IN (?, ?) AND status = 'ACTIVE'
                """, Integer.class, blogId, a, b);
        return n != null && n == 2;
    }

    /** 이 블로그 안에서 viewer에게 숨길 회원: 서로 차단한 사이이면서 둘 다 이 블로그 멤버는 아닌 사람. */
    @Transactional(readOnly = true)
    public Set<Long> hiddenIn(long blogId, long viewer) {
        Set<Long> ids = new HashSet<>(jdbc.queryForList("""
                SELECT blocked_id FROM blocks WHERE blocker_id = ?
                UNION SELECT blocker_id FROM blocks WHERE blocked_id = ?
                """, Long.class, viewer, viewer));
        if (ids.isEmpty()) {
            return ids;
        }
        Integer viewerIsMember = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blog_memberships WHERE blog_id = ? AND member_id = ? AND status = 'ACTIVE'",
                Integer.class, blogId, viewer);
        if (viewerIsMember != null && viewerIsMember > 0) {
            ids.removeAll(jdbc.queryForList("SELECT member_id FROM blog_memberships WHERE blog_id = ? AND status = 'ACTIVE'",
                    Long.class, blogId));
        }
        return ids;
    }

    /** 이 블로그 안에서 a가 b에게 댓글·좋아요를 할 수 없으면 막는다. */
    public void requireNotBlocked(long blogId, long actor, long other, String action) {
        if (actor != other && between(actor, other) && !sameBlog(blogId, actor, other)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "차단한 사이에는 %s 할 수 없습니다.".formatted(action));
        }
    }

    /** 누르면 차단, 한 번 더 누르면 해제. 차단하면 서로의 팔로우가 풀린다 (US4-1). */
    @Transactional
    public boolean toggle(long blocker, long blocked) {
        if (jdbc.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", blocker, blocked) == 1) {
            return false;
        }
        jdbc.update("INSERT IGNORE INTO blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)", blocker, blocked,
                LocalDateTime.now(clock));
        jdbc.update("""
                DELETE FROM follows WHERE (follower_id = ? AND followee_id = ?) OR (follower_id = ? AND followee_id = ?)
                """, blocker, blocked, blocked, blocker);
        return true;
    }
}
