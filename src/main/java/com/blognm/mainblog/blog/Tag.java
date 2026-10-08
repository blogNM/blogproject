package com.blognm.mainblog.blog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 태그. 블로그 태그(BLG-03)와 3단계 글 태그가 같은 표를 쓴다. 소문자로 저장한다. */
@Entity
@Table(name = "tags")
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected Tag() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
