package com.blognm.mainblog.blog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.config.AppProperties;

/**
 * 블로그 주소는 /blog/{주소} (D-70). 화면은 정적 HTML이고, 화면의 JS가 주소에서 블로그를 읽는다.
 * 블로그 홈은 서버가 og 태그(블로그 이름·소개·대표 이미지)를 채워 보낸다. 카톡 등 미리보기는 JS를 실행하지 않기 때문이다 (D-96).
 */
@Controller
public class BlogPageController {

    private static final String OG_MARK = "<!--OG-->";
    /** 대표 이미지가 없는 블로그의 미리보기 그림 (사이트 로고) */
    private static final String DEFAULT_IMAGE = "/img/og-default.png";

    private final BlogService blogService;
    private final BlogRepository blogs;
    private final AppProperties props;
    private final String blogTemplate;

    public BlogPageController(BlogService blogService, BlogRepository blogs, AppProperties props) {
        this.blogService = blogService;
        this.blogs = blogs;
        this.props = props;
        try {
            this.blogTemplate = new ClassPathResource("static/blog.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/blog/{slug}")
    public ResponseEntity<String> home(@PathVariable String slug, @RequestParam(required = false) String key) {
        String html = blogTemplate.replace(OG_MARK, visible(slug, key).map(b -> ogTags(b, slug, key)).orElse(""));
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
    }

    @GetMapping("/blog/{slug}/manage")
    public String manage(@PathVariable String slug) {
        return "forward:/blog-manage.html";
    }

    /** 로그인하지 않은 사람이 볼 수 있는 블로그만 미리보기를 채운다. 비공개 블로그는 이름도 내보내지 않는다. */
    private Optional<Blog> visible(String slug, String key) {
        try {
            return Optional.of(blogService.visibleBlog(slug, key, null));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    /** 미리보기에는 블로그 이름 → 소개 → 대표 이미지(없으면 사이트 로고)가 보인다. 값은 모두 HTML로 이스케이프한다. */
    private String ogTags(Blog blog, String slug, String key) {
        String base = props.baseUrl().replaceAll("/+$", "");
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(base).path("/blog/{slug}");
        if (key != null) {
            url.queryParam("key", "{key}");
        }
        String pageUrl = key == null ? url.buildAndExpand(slug).encode().toUriString()
                : url.buildAndExpand(slug, key).encode().toUriString();
        String description = blog.getDescription() == null || blog.getDescription().isBlank()
                ? blog.getName() + " 블로그입니다."
                : blog.getDescription();
        String image = blogs.coverImageName(blog.getId()).map(name -> "/uploads/" + name).orElse(DEFAULT_IMAGE);
        StringBuilder tags = new StringBuilder();
        tags.append(meta("og:type", "website"));
        tags.append(meta("og:site_name", "Creat_"));
        tags.append(meta("og:url", pageUrl));
        tags.append(meta("og:title", blog.getName()));
        tags.append(meta("og:description", description));
        tags.append(meta("og:image", base + image));
        tags.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        tags.append("<meta name=\"description\" content=\"").append(HtmlUtils.htmlEscape(description, "UTF-8"))
                .append("\">\n");
        return tags.toString();
    }

    private static String meta(String property, String content) {
        return "<meta property=\"" + property + "\" content=\"" + HtmlUtils.htmlEscape(content, "UTF-8") + "\">\n";
    }
}
