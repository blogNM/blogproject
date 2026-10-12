package com.blognm.mainblog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import jakarta.servlet.http.Cookie;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 글 상세·블로그 홈·사이트 첫 화면의 og 태그 (D-66, D-59, D-96, D-100), 글쓰기 화면 주소 */
class OgTagTest extends IntegrationTest {

    private Cookie[] owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        postAs("/api/blogs", """
                {"slug":"food","name":"맛집 \\"블로그\\"","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
        postAs("/api/blogs", """
                {"slug":"secret","name":"비밀","visibility":"PRIVATE","joinPolicy":"APPROVAL"}""", owner)
                .andExpect(status().isOk());
    }

    private long write(String slug, String title, String content) throws Exception {
        String body = postAs("/api/blogs/" + slug + "/posts", """
                {"title":"%s","content":"%s"}""".formatted(title, content), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    @DisplayName("글 상세 주소는 서버가 og 태그를 채워 보낸다. 값은 이스케이프한다")
    void ogTags() throws Exception {
        long id = write("food", "<script>\\\"제목\\\"", "**국밥** 맛집 이야기");
        String html = mvc.perform(MockMvcRequestBuilders.get("/blog/food/posts/" + id))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", Matchers.containsString("text/html")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<meta property=\"og:title\" content=\"&lt;script&gt;&quot;제목&quot;\">");
        assertThat(html).contains("<meta property=\"og:description\" content=\"국밥 맛집 이야기\">");
        assertThat(html).contains("og:url\" content=\"http://localhost:8080/blog/food/posts/" + id + "\"");
        assertThat(html).doesNotContain("<script>\"");
        // 글에 사진이 없고 블로그에 머리 사진도 없으면 블로그 머리 배경 그림을 쓴다 (D-100)
        assertThat(html).contains("<meta property=\"og:image\" content=\"http://localhost:8080/blog/food/og-image.png\">");
        assertThat(html).contains("<meta name=\"twitter:card\" content=\"summary_large_image\">");
    }

    @Test
    @DisplayName("비회원이 볼 수 없는 글은 og 태그를 채우지 않는다")
    void privateNoOg() throws Exception {
        long id = write("secret", "비밀 글", "비밀 본문");
        String html = mvc.perform(MockMvcRequestBuilders.get("/blog/secret/posts/" + id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("og:title").doesNotContain("비밀 본문");
    }

    @Test
    @DisplayName("블로그 주소는 서버가 블로그 이름·소개와 머리 사진(없으면 머리 배경 그림)을 og 태그로 채운다 (D-96, D-100)")
    void blogOgTags() throws Exception {
        String html = mvc.perform(MockMvcRequestBuilders.get("/blog/food"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", Matchers.containsString("text/html")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<meta property=\"og:title\" content=\"맛집 &quot;블로그&quot;\">");
        assertThat(html).contains("<meta property=\"og:description\" content=\"맛집 &quot;블로그&quot; 블로그입니다.\">");
        assertThat(html).contains("<meta property=\"og:image\" content=\"http://localhost:8080/blog/food/og-image.png\">");
        assertThat(html).contains("og:url\" content=\"http://localhost:8080/blog/food\"");
        assertThat(html).contains("/js/pages/blog.js");
    }

    @Test
    @DisplayName("머리 배경 그림은 1200×630 PNG. 비회원이 볼 수 없는 블로그는 404 (D-100)")
    void blogOgImage() throws Exception {
        byte[] png = mvc.perform(MockMvcRequestBuilders.get("/blog/food/og-image.png"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(1200);
        assertThat(image.getHeight()).isEqualTo(630);
        mvc.perform(MockMvcRequestBuilders.get("/blog/secret/og-image.png")).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/blog/nope/og-image.png")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("사이트 첫 화면은 사이트 이름과 로고·사이트 색 그림을 og 태그로 채운다 (D-100)")
    void siteOgTags() throws Exception {
        String html = mvc.perform(MockMvcRequestBuilders.get("/"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", Matchers.containsString("text/html")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<meta property=\"og:title\" content=\"Creat_ - 함께 만드는 블로그\">");
        assertThat(html).contains("<meta property=\"og:image\" content=\"http://localhost:8080/img/og-default.png\">");
        assertThat(html).contains("og:url\" content=\"http://localhost:8080/\"");
        assertThat(html).contains("/js/pages/index.js").doesNotContain("<!--OG-->");
    }

    @Test
    @DisplayName("비회원이 볼 수 없는 블로그는 og 태그를 채우지 않는다")
    void privateBlogNoOg() throws Exception {
        String html = mvc.perform(MockMvcRequestBuilders.get("/blog/secret"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("og:title").doesNotContain("비밀");
        mvc.perform(MockMvcRequestBuilders.get("/blog/nope")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("글쓰기·수정 화면 주소는 글쓰기 화면을 보여준다. 이 화면만 인라인 style을 허용한다")
    void editorPages() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/blog/food/write")).andExpect(forwardedUrl("/post-edit.html"))
                .andExpect(header().string("Content-Security-Policy", Matchers.containsString("style-src 'self' 'unsafe-inline'")))
                .andExpect(header().string("Content-Security-Policy", Matchers.containsString("script-src 'self' https://challenges.cloudflare.com;")));
        mvc.perform(MockMvcRequestBuilders.get("/blog/food/posts/1/edit")).andExpect(forwardedUrl("/post-edit.html"));
        mvc.perform(MockMvcRequestBuilders.get("/blog/food"))
                .andExpect(header().string("Content-Security-Policy", Matchers.containsString("style-src 'self';")));
    }
}
