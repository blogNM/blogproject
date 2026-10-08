package com.blognm.mainblog.blog;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, Long> {

    Optional<JoinRequest> findFirstByBlogIdAndMemberIdOrderByIdDesc(long blogId, long memberId);

    /** 블로그장 두 명(블로그장·부블로그장)이 같은 신청을 동시에 처리해도 한 번만 처리된다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from JoinRequest r where r.id = :id")
    Optional<JoinRequest> findForUpdate(long id);

    @Query("select r from JoinRequest r where r.blogId = :blogId"
            + " and r.status = com.blognm.mainblog.blog.JoinRequest.Status.PENDING order by r.id")
    List<JoinRequest> findPending(long blogId);

    @Query("select r from JoinRequest r where r.memberId = :memberId"
            + " and r.status = com.blognm.mainblog.blog.JoinRequest.Status.PENDING order by r.id desc")
    List<JoinRequest> findPendingByMember(long memberId);

    @Modifying
    @Query("delete from JoinRequest r where r.memberId = :memberId"
            + " and r.status = com.blognm.mainblog.blog.JoinRequest.Status.PENDING")
    int deletePendingByMember(long memberId);
}
