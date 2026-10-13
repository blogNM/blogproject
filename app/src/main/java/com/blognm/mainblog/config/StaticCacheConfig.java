package com.blognm.mainblog.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 정적 파일 캐시 (D-109). Spring Security는 따로 정하지 않은 응답에 모두 "no-store"를 붙여서,
 * 새로고침할 때마다 글꼴(2MB)·스크립트·그림을 처음부터 다시 받았다.
 * - 글꼴·에디터(/vendor)와 그림(/img)은 배포 사이에 거의 바뀌지 않아 브라우저에 오래 둔다.
 * - 화면(html)·js·css는 application.yml의 spring.web.resources.cache.cachecontrol.no-cache로
 *   "가지고는 있되 쓸 때마다 바뀌었는지 묻기"로 둔다. 안 바뀌었으면 304(빈 응답)라 빠르고, 배포하면 바로 새 파일을 받는다.
 * 여기서 Cache-Control을 정하면 Spring Security는 덮어쓰지 않는다.
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    private final Duration vendorMaxAge;
    private final Duration imageMaxAge;

    public StaticCacheConfig(@Value("${static-cache.vendor-max-age:30d}") Duration vendorMaxAge,
            @Value("${static-cache.image-max-age:7d}") Duration imageMaxAge) {
        this.vendorMaxAge = vendorMaxAge;
        this.imageMaxAge = imageMaxAge;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/vendor/**").addResourceLocations("classpath:/static/vendor/")
                .setCacheControl(CacheControl.maxAge(vendorMaxAge).cachePublic());
        registry.addResourceHandler("/img/**").addResourceLocations("classpath:/static/img/")
                .setCacheControl(CacheControl.maxAge(imageMaxAge).cachePublic());
    }
}
