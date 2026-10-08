package com.blognm.mainblog.blog;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/** application.yml의 blog.* 값 (BLG-10, D-52). */
@ConfigurationProperties(prefix = "blog")
public record BlogProperties(Limit limit, int pageSize, Duration rejoinAfterReject) {

    /** 공개 블로그 수는 5개까지만 올릴 수 있다 (D-51). */
    public static final int MAX_PUBLIC_LIMIT = 5;

    public BlogProperties {
        if (limit.publicCount() < 1 || limit.publicCount() > MAX_PUBLIC_LIMIT) {
            throw new IllegalStateException("BLOG_LIMIT_PUBLIC는 1~" + MAX_PUBLIC_LIMIT + " 사이로 넣어 주세요.");
        }
    }

    public record Limit(@Name("public") int publicCount, @Name("private") int privateCount) {
    }
}
