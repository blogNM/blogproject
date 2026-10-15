package com.blognm.mainblog.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

import com.blognm.mainblog.config.AppProperties;

/**
 * 사이트 첫 화면. 화면은 정적 HTML이고, 사이트 주소를 공유했을 때 미리보기에 사이트 이름·소개와
 * 사이트 로고·색 그림(/img/og-default.png)이 나오도록 서버가 og 태그를 채운다 (D-100).
 */
@Controller
public class HomePageController {

    private static final String OG_MARK = "<!--OG-->";
    private static final String TITLE = "Creat_ - 함께 만드는 블로그";
    private static final String DESCRIPTION = "블로그를 만들고, 테마 틀을 골라 꾸미고, 다른 회원과 함께 운영해 보세요.";
    private static final String IMAGE = "/img/og-default.png";

    private final String html;

    public HomePageController(AppProperties props) {
        String base = props.baseUrl().replaceAll("/+$", "");
        try {
            String template = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
            this.html = template.replace(OG_MARK, ogTags(base));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/")
    public ResponseEntity<String> home() {
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
    }

    private static String ogTags(String base) {
        StringBuilder tags = new StringBuilder();
        tags.append(meta("og:type", "website"));
        tags.append(meta("og:site_name", "Creat_"));
        tags.append(meta("og:url", base + "/"));
        tags.append(meta("og:title", TITLE));
        tags.append(meta("og:description", DESCRIPTION));
        tags.append(meta("og:image", base + IMAGE));
        tags.append(meta("og:image:width", "1200"));
        tags.append(meta("og:image:height", "630"));
        tags.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        tags.append("<meta name=\"description\" content=\"").append(HtmlUtils.htmlEscape(DESCRIPTION, "UTF-8"))
                .append("\">\n");
        return tags.toString();
    }

    private static String meta(String property, String content) {
        return "<meta property=\"" + property + "\" content=\"" + HtmlUtils.htmlEscape(content, "UTF-8") + "\">\n";
    }
}
