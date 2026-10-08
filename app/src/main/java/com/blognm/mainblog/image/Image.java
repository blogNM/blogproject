package com.blognm.mainblog.image;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 올린 이미지 (BRD-05). 파일은 UUID 이름으로 두고 원래 이름은 DB에만 남긴다 (6.3). */
@Entity
@Table(name = "images")
public class Image {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uploader_id")
    private Long uploaderId;

    /** 글에 붙기 전에는 비어 있다. 하루 지나도 붙지 않으면 지운다. */
    @Column(name = "post_id")
    private Long postId;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "stored_name")
    private String storedName;

    @Column(name = "original_name")
    private String originalName;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "size_bytes")
    private int sizeBytes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    protected Image() {
    }

    public static Image uploaded(long uploaderId, String storedName, String originalName, String mimeType,
            int sizeBytes, LocalDateTime now) {
        Image i = new Image();
        i.uploaderId = uploaderId;
        i.storedName = storedName;
        i.originalName = originalName;
        i.mimeType = mimeType;
        i.sizeBytes = sizeBytes;
        i.createdAt = now;
        return i;
    }

    public void attachTo(long postId, int sortOrder) {
        this.postId = postId;
        this.sortOrder = sortOrder;
    }

    public void detach() {
        this.postId = null;
        this.sortOrder = 0;
    }

    public String url() {
        return "/uploads/" + storedName;
    }

    public Long getId() {
        return id;
    }

    public Long getUploaderId() {
        return uploaderId;
    }

    public Long getPostId() {
        return postId;
    }

    public String getStoredName() {
        return storedName;
    }

    public String getMimeType() {
        return mimeType;
    }
}
