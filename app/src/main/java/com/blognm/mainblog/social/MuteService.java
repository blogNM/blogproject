package com.blognm.mainblog.social;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.common.ApiException;

/**
 * 관심 없음 (SOC-08, D-123). 회원이나 블로그를 나에게만 숨긴다.
 * 메인 피드·통합 검색·블로그 목록에서 빠지고, 프로필이나 블로그 홈에 직접 들어가면 그대로 보인다.
 * 상대에게 알리지 않고 상대 화면도 바뀌지 않는다. 내 팔로우·구독은 풀린다 (보고 싶지 않은데 받아 볼 이유가 없음).
 */
@Service
public class MuteService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MuteService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 누르면 숨기고, 한 번 더 누르면 푼다. 숨기면 내가 한 팔로우가 풀린다. */
    @Transactional
    public boolean toggleMember(long memberId, long mutedId) {
        if (jdbc.update("DELETE FROM member_mutes WHERE member_id = ? AND muted_id = ?", memberId, mutedId) == 1) {
            return false;
        }
        jdbc.update("INSERT IGNORE INTO member_mutes (member_id, muted_id, created_at) VALUES (?, ?, ?)", memberId,
                mutedId, LocalDateTime.now(clock));
        jdbc.update("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?", memberId, mutedId);
        return true;
    }

    /**
     * 누르면 숨기고, 한 번 더 누르면 푼다. 숨기면 내 구독이 풀린다.
     * 숨길 수 있는 블로그는 누구나 볼 수 있는 공개 블로그뿐이다. 비공개·링크 공개·숨김 블로그는 원래 목록에 나오지 않고,
     * 주소를 짐작해 숨겨 보는 것으로 있는지와 이름을 알아내지 못하게 404로 답한다. 이미 숨긴 것은 언제든 풀 수 있다.
     */
    @Transactional
    public boolean toggleBlog(long memberId, String slug) {
        Long blogId = jdbc.query("SELECT id FROM blogs WHERE slug = ? AND status <> 'CLOSED'",
                (rs, i) -> rs.getLong("id"), slug).stream().findFirst().orElseThrow(MuteService::blogNotFound);
        if (jdbc.update("DELETE FROM blog_mutes WHERE member_id = ? AND blog_id = ?", memberId, blogId) == 1) {
            return false;
        }
        if (count("SELECT COUNT(*) FROM blogs WHERE id = ? AND " + LISTED_BLOG, blogId) == 0) {
            throw blogNotFound();
        }
        jdbc.update("INSERT IGNORE INTO blog_mutes (member_id, blog_id, created_at) VALUES (?, ?, ?)", memberId, blogId,
                LocalDateTime.now(clock));
        jdbc.update("DELETE FROM subscriptions WHERE member_id = ? AND blog_id = ?", memberId, blogId);
        return true;
    }

    @Transactional(readOnly = true)
    public boolean memberMuted(Long memberId, long mutedId) {
        return memberId != null && count("SELECT COUNT(*) FROM member_mutes WHERE member_id = ? AND muted_id = ?",
                memberId, mutedId) > 0;
    }

    @Transactional(readOnly = true)
    public boolean blogMuted(Long memberId, long blogId) {
        return memberId != null && count("SELECT COUNT(*) FROM blog_mutes WHERE member_id = ? AND blog_id = ?",
                memberId, blogId) > 0;
    }

    /** 내 정보의 "관심 없음" 목록. 최근에 숨긴 순서. 탈퇴한 회원, 폐쇄됐거나 이제 공개가 아닌 블로그는 빼고 보여 준다. */
    @Transactional(readOnly = true)
    public Muted list(long memberId) {
        List<MutedMember> members = jdbc.query("""
                SELECT x.id, x.nickname, x.is_test FROM member_mutes u JOIN members x ON x.id = u.muted_id
                WHERE u.member_id = ? AND x.status = 'ACTIVE' ORDER BY u.created_at DESC, x.id DESC
                """, (rs, i) -> new MutedMember(rs.getLong("id"), rs.getString("nickname"), rs.getBoolean("is_test")),
                memberId);
        List<MutedBlog> blogs = jdbc.query("""
                SELECT b.slug, b.name FROM blog_mutes u JOIN blogs b ON b.id = u.blog_id
                WHERE u.member_id = ? AND b.visibility = 'PUBLIC' AND b.is_hidden = 0 AND b.status <> 'CLOSED'
                ORDER BY u.created_at DESC, b.id DESC
                """, (rs, i) -> new MutedBlog(rs.getString("slug"), rs.getString("name")), memberId);
        return new Muted(members, blogs);
    }

    /** 누구나 목록에서 볼 수 있는 블로그 */
    private static final String LISTED_BLOG = "visibility = 'PUBLIC' AND is_hidden = 0 AND status <> 'CLOSED'";

    private static ApiException blogNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BLOG_NOT_FOUND", "블로그를 찾을 수 없습니다.");
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    public record MutedMember(long id, String nickname, boolean test) {
    }

    public record MutedBlog(String slug, String name) {
    }

    public record Muted(List<MutedMember> members, List<MutedBlog> blogs) {
    }
}
