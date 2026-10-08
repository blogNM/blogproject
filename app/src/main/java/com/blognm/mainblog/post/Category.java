package com.blognm.mainblog.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 블로그별 카테고리 (BRD-03). 블로그장 또는 글 관리 권한이 있는 부블로그장이 관리한다. */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blog_id")
    private Long blogId;

    private String name;

    @Column(name = "sort_order")
    private int sortOrder;

    protected Category() {
    }

    public static Category create(long blogId, String name, int sortOrder) {
        Category c = new Category();
        c.blogId = blogId;
        c.name = name;
        c.sortOrder = sortOrder;
        return c;
    }

    public void rename(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getBlogId() {
        return blogId;
    }

    public String getName() {
        return name;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
