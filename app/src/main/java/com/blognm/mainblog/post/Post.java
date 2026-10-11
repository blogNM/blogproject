package com.blognm.mainblog.post;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import com.blognm.mainblog.blog.Tag;

/** 글 (BRD-01). 본문은 마크다운 원문으로 저장하고, 보여줄 때 서버가 HTML로 바꿔 거른다 (D-64). */
@Entity
@Table(name = "posts")
public class Post {

    /** 숨김은 관리자 조치(6단계), 삭제는 30일 뒤 완전히 지운다 (US2-4). */
    public enum Status { NORMAL, HIDDEN, DELETED }

    /** 블로그장과 글 관리 권한이 있는 부블로그장은 OWNER로 남긴다. */
    public enum DeletedBy { AUTHOR, OWNER, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blog_id")
    private Long blogId;

    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "category_id")
    private Long categoryId;

    private String title;

    @Column(name = "content_md")
    private String contentMd;

    @Column(name = "is_notice")
    private boolean notice;

    @Column(name = "author_left")
    private boolean authorLeft;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "deleted_by")
    private DeletedBy deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 조회수·좋아요·댓글 수는 4단계에서 세고, 여기서는 읽기만 한다. */
    @Column(name = "view_count", insertable = false, updatable = false)
    private int viewCount;

    @Column(name = "like_count", insertable = false, updatable = false)
    private int likeCount;

    @Column(name = "comment_count", insertable = false, updatable = false)
    private int commentCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToMany
    @OrderBy("name")
    @JoinTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    protected Post() {
    }

    public static Post write(long blogId, long authorId, LocalDateTime now) {
        Post p = new Post();
        p.blogId = blogId;
        p.authorId = authorId;
        p.status = Status.NORMAL;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    public void change(String title, String contentMd, Long categoryId, Set<Tag> newTags, LocalDateTime now) {
        this.title = title;
        this.contentMd = contentMd;
        this.categoryId = categoryId;
        this.tags.clear();
        this.tags.addAll(newTags);
        this.updatedAt = now;
    }

    public void markNotice(boolean notice) {
        this.notice = notice;
    }

    public void delete(DeletedBy by, LocalDateTime now) {
        this.status = Status.DELETED;
        this.deletedBy = by;
        this.deletedAt = now;
    }

    public boolean isNormal() {
        return status == Status.NORMAL;
    }

    public Long getId() {
        return id;
    }

    public Long getBlogId() {
        return blogId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getContentMd() {
        return contentMd;
    }

    public boolean isNotice() {
        return notice;
    }

    public boolean isAuthorLeft() {
        return authorLeft;
    }

    public Status getStatus() {
        return status;
    }

    public DeletedBy getDeletedBy() {
        return deletedBy;
    }

    public int getViewCount() {
        return viewCount;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Set<Tag> getTags() {
        return tags;
    }
}
