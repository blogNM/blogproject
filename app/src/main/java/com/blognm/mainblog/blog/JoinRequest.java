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

/** 승인제 블로그의 참여 신청 (BLG-04, BLG-05). */
@Entity
@Table(name = "join_requests")
public class JoinRequest {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blog_id")
    private Long blogId;

    @Column(name = "member_id")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processed_by")
    private Long processedBy;

    protected JoinRequest() {
    }

    public static JoinRequest pending(long blogId, long memberId, LocalDateTime now) {
        JoinRequest r = new JoinRequest();
        r.blogId = blogId;
        r.memberId = memberId;
        r.status = Status.PENDING;
        r.requestedAt = now;
        return r;
    }

    public void approve(long actorId, LocalDateTime now) {
        process(Status.APPROVED, actorId, now);
    }

    public void reject(long actorId, LocalDateTime now) {
        process(Status.REJECTED, actorId, now);
    }

    private void process(Status result, long actorId, LocalDateTime now) {
        this.status = result;
        this.processedBy = actorId;
        this.processedAt = now;
    }

    public boolean isPending() {
        return status == Status.PENDING;
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

    public Status getStatus() {
        return status;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }
}
