package com.blognm.mainblog.blog;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.blognm.mainblog.auth.MemberPrincipal;

/**
 * 블로그별 역할 확인. 컨트롤러에서 {@code @PreAuthorize("@blogAuth.canManageMembers(#slug)")}처럼 쓴다.
 * 토큰이 아니라 매 요청 DB로 역할·정지 여부를 다시 본다 (헌법 II, SEC-07).
 * 폐쇄된 블로그와 정지 중인 멤버는 어떤 권한도 없다 (BLG-13).
 */
@Component("blogAuth")
public class BlogAuth {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BlogAuth(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean isMember(String slug) {
        return current(slug).isPresent();
    }

    public boolean isOwner(String slug) {
        return current(slug).filter(r -> r.role() == BlogMembership.Role.OWNER).isPresent();
    }

    /** 참여 승인·거절, 멤버 정보 보기. 블로그장 또는 멤버 관리 권한이 있는 부블로그장. */
    public boolean canManageMembers(String slug) {
        return current(slug).filter(r -> r.role() == BlogMembership.Role.OWNER
                || (r.role() == BlogMembership.Role.SUB_OWNER && r.manageMembers())).isPresent();
    }

    /** 블로그 이름·소개·태그 수정. 블로그장 또는 정보 수정 권한이 있는 부블로그장. */
    public boolean canEditInfo(String slug) {
        return current(slug).filter(r -> r.role() == BlogMembership.Role.OWNER
                || (r.role() == BlogMembership.Role.SUB_OWNER && r.editInfo())).isPresent();
    }

    /** 다른 멤버의 글 삭제, 카테고리 관리, 블로그 공지. 블로그장 또는 글 관리 권한이 있는 부블로그장 (D-71). */
    public boolean canManagePosts(String slug) {
        return current(slug).filter(r -> r.role() == BlogMembership.Role.OWNER
                || (r.role() == BlogMembership.Role.SUB_OWNER && r.managePosts())).isPresent();
    }

    /** 블로그 통계 보기 (D-112). 블로그 관리 화면에 들어오는 사람(블로그장·부블로그장)은 모두 본다. */
    public boolean canViewStats(String slug) {
        return current(slug).filter(r -> r.role() == BlogMembership.Role.OWNER
                || r.role() == BlogMembership.Role.SUB_OWNER).isPresent();
    }

    private Optional<RoleRow> current(String slug) {
        Long memberId = currentMemberId();
        if (memberId == null || slug == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<RoleRow> rows = jdbc.query("""
                SELECT bm.role, bm.perm_edit_info, bm.perm_manage_members, bm.perm_manage_posts, bm.suspended_until
                FROM blog_memberships bm JOIN blogs b ON b.id = bm.blog_id
                WHERE b.slug = ? AND bm.member_id = ? AND bm.status = 'ACTIVE' AND b.status <> 'CLOSED'
                """,
                (rs, i) -> new RoleRow(BlogMembership.Role.valueOf(rs.getString("role")),
                        rs.getBoolean("perm_edit_info"), rs.getBoolean("perm_manage_members"),
                        rs.getBoolean("perm_manage_posts"),
                        rs.getObject("suspended_until", LocalDateTime.class)),
                slug, memberId);
        return rows.stream().findFirst().filter(r -> r.suspendedUntil() == null || !r.suspendedUntil().isAfter(now));
    }

    static Long currentMemberId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof MemberPrincipal p ? p.memberId() : null;
    }

    private record RoleRow(BlogMembership.Role role, boolean editInfo, boolean manageMembers, boolean managePosts,
            LocalDateTime suspendedUntil) {
    }
}
