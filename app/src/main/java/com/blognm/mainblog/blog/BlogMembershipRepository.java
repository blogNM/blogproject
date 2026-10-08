package com.blognm.mainblog.blog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BlogMembershipRepository extends JpaRepository<BlogMembership, Long> {

    Optional<BlogMembership> findByBlogIdAndMemberId(long blogId, long memberId);

    @Query("select count(m) from BlogMembership m where m.blogId = :blogId"
            + " and m.status = com.blognm.mainblog.blog.BlogMembership.Status.ACTIVE")
    long countActive(long blogId);

    @Query("select m from BlogMembership m where m.blogId = :blogId"
            + " and m.status = com.blognm.mainblog.blog.BlogMembership.Status.ACTIVE order by m.id")
    List<BlogMembership> findActiveByBlogId(long blogId);

    /** 회원탈퇴하면 참여한 블로그에서 모두 나간 것으로 둔다. */
    @Modifying
    @Query(value = """
            UPDATE blog_memberships SET status = 'LEFT', role = 'MEMBER', left_at = :now,
              perm_edit_info = FALSE, perm_manage_members = FALSE, perm_manage_posts = FALSE, sub_owner_since = NULL
            WHERE member_id = :memberId AND status = 'ACTIVE'
            """, nativeQuery = true)
    int leaveAll(long memberId, java.time.LocalDateTime now);
}
