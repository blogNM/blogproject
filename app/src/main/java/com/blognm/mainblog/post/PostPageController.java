package com.blognm.mainblog.post;

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

import com.blognm.mainblog.config.AppProperties;

/**
 * 글 화면 주소. 화면은 정적 HTML이고, 글 상세만 서버가 og 태그(제목·요약·이미지)를 채워 보낸다.
 * 카톡·X 미리보기는 JS를 실행하지 않기 때문이다 (D-66, D-59).
 */
@Controller
public class PostPageController {

    private static final String OG_MARK = "<!--OG-->";

    private final PostService posts;
    private final AppProperties props;
    private final String postTemplate;

    public PostPageController(PostService posts, AppProperties props) {
        this.posts = posts;
        this.props = props;
        try {
            this.postTemplate = new ClassPathResource("static/post.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/blog/{slug}/write")
    public String write(@PathVariable String slug) {
        return "forward:/post-edit.html";
    }

    @GetMapping("/blog/{slug}/posts/{id}/edit")
    public String edit(@PathVariable String slug, @PathVariable long id) {
        return "forward:/post-edit.html";
    }

    @GetMapping("/blog/{slug}/posts/{id}")
    public ResponseEntity<String> view(@PathVariable String slug, @PathVariable long id,
            @RequestParam(required = false) String key) {
        Optional<PostService.OgInfo> og = posts.og(slug.toLowerCase(), id, key);
        String html = postTemplate.replace(OG_MARK, og.map(o -> ogTags(o, slug, id, key)).orElse(""));
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
    }

    /** 미리보기에는 주소 → 제목 → 이미지 순서로 보인다 (D-59). 값은 모두 HTML로 이스케이프한다. */
    private String ogTags(PostService.OgInfo og, String slug, long id, String key) {
        String base = props.baseUrl().replaceAll("/+$", "");
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(base).path("/blog/{slug}/posts/{id}");
        if (key != null) {
            url.queryParam("key", "{key}");
        }
        String pageUrl = key == null ? url.buildAndExpand(slug, id).encode().toUriString()
                : url.buildAndExpand(slug, id, key).encode().toUriString();
        StringBuilder tags = new StringBuilder();
        tags.append(meta("og:type", "article"));
        tags.append(meta("og:site_name", "Creat_"));
        tags.append(meta("og:url", pageUrl));
        tags.append(meta("og:title", og.title() + " - " + og.blogName()));
        tags.append(meta("og:description", og.description()));
        if (og.imagePath() != null) {
            tags.append(meta("og:image", base + og.imagePath()));
        }
        tags.append("<meta name=\"twitter:card\" content=\"")
                .append(og.imagePath() != null ? "summary_large_image" : "summary").append("\">\n");
        tags.append("<meta name=\"description\" content=\"").append(HtmlUtils.htmlEscape(og.description()))
                .append("\">\n");
        return tags.toString();
    }

    private static String meta(String property, String content) {
        return "<meta property=\"" + property + "\" content=\"" + HtmlUtils.htmlEscape(content, "UTF-8") + "\">\n";
    }
}
