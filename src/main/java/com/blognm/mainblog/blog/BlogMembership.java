package com.blognm.mainblog.blog;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 회원 × 블로그. 블로그장·부블로그장·멤버는 계정 종류가 아니라 블로그별 역할이다 (헌법 II). */
@Entity
@Table(name = "blog_memberships")
public class BlogMembership {

    public enum Role { OWNER, SUB_OWNER, MEMBER }

    public enum Status { ACTIVE, LEFT, KICKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blog_id")
    private Long blogId;

    @Column(name = "member_id")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "perm_edit_info")
    private boolean permEditInfo;

    @Column(name = "perm_manage_members")
    private boolean permManageMembers;

    @Column(name = "perm_manage_posts")
    private boolean permManagePosts;

    @Column(name = "sub_owner_since")
    private LocalDateTime subOwnerSince;

    @Column(name = "suspended_until")
    private LocalDateTime suspendedUntil;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    protected BlogMembership() {
    }

    public static BlogMembership owner(long blogId, long memberId, LocalDateTime now) {
        BlogMembership m = member(blogId, memberId, now);
        m.role = Role.OWNER;
        return m;
    }

    public static BlogMembership member(long blogId, long memberId, LocalDateTime now) {
        BlogMembership m = new BlogMembership();
        m.blogId = blogId;
        m.memberId = memberId;
        m.role = Role.MEMBER;
        m.status = Status.ACTIVE;
        m.joinedAt = now;
        return m;
    }

    /** 스스로 나갔던 회원이 다시 들어온다. 한 블로그에 한 행만 두므로 그 행을 다시 쓴다. */
    public void rejoin(LocalDateTime now) {
        this.role = Role.MEMBER;
        this.status = Status.ACTIVE;
        this.joinedAt = now;
        this.leftAt = null;
        clearSubOwner();
    }

    public void leave(LocalDateTime now) {
        this.status = Status.LEFT;
        this.role = Role.MEMBER;
        this.leftAt = now;
        clearSubOwner();
    }

    private void clearSubOwner() {
        this.permEditInfo = false;
        this.permManageMembers = false;
        this.permManagePosts = false;
        this.subOwnerSince = null;
    }

    /** 블로그장 또는 멤버 관리 권한이 있는 부블로그장 (D-71). */
    public boolean canManageMembers() {
        return role == Role.OWNER || (role == Role.SUB_OWNER && permManageMembers);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public Long getBlogId() {
        return blogId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Role getRole() {
        return role;
    }

    public Status getStatus() {
        return status;
    }

    public LocalDateTime getSuspendedUntil() {
        return suspendedUntil;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }
}
