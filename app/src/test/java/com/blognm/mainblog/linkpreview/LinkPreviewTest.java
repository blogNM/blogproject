package com.blognm.mainblog.linkpreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

/** 글 속 링크 미리보기 카드 (D-92). 이 컴퓨터에 띄운 가짜 사이트에서 받는다. */
class LinkPreviewTest extends IntegrationTest {

    private HttpServer site;
    private String base;
    private final AtomicInteger pageHits = new AtomicInteger();
    private Cookie[] owner;

    @BeforeEach
    void setUp() throws Exception {
        byte[] png = png();
        site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/song", ex -> {
            pageHits.incrementAndGet();
            byte[] html = """
                    <html><head><title>예비 제목</title>
                    <meta property="og:title" content="봄날 &lt;b&gt;라이브&lt;/b&gt;">
                    <meta property="og:description" content="<script>alert(1)</script>설명입니다">
                    <meta property="og:site_name" content="음악 사이트">
                    <meta property="og:image" content="/thumb.png">
                    </head><body>본문</body></html>""".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            ex.getResponseBody().write(html);
            ex.close();
        });
        site.createContext("/thumb.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
            ex.close();
        });
        site.createContext("/fake.png", ex -> {
            byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, svg.length);
            ex.getResponseBody().write(svg);
            ex.close();
        });
        site.createContext("/svg-page", ex -> {
            byte[] html = """
                    <html><head><meta property="og:title" content="사진 없는 카드">
                    <meta property="og:image" content="/fake.png"></head></html>""".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, html.length);
            ex.getResponseBody().write(html);
            ex.close();
        });
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
        owner = member("owner@example.com", "블로그장");
        postAs("/api/blogs", """
                {"slug":"music","name":"음악","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
    }

    @AfterEach
    void stop() {
        site.stop(0);
    }

    private String html(String content) throws Exception {
        String body = postAs("/api/blogs/music/posts", """
                {"title":"링크","content":"%s","tags":[]}""".formatted(content), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        return JsonPath.read(getAs("/api/posts/" + id).andReturn().getResponse().getContentAsString(), "$.contentHtml");
    }

    @Test
    @DisplayName("한 줄에 링크만 있으면 썸네일·제목·설명 카드로 보인다. 문장 속 링크는 그대로 둔다")
    void card() throws Exception {
        String html = html(base + "/song\\n\\n노래는 [여기](" + base + "/song)에서 들어요.");
        assertThat(html).contains("<a class=\"link-card\" href=\"" + base + "/song\"",
                "rel=\"nofollow noopener noreferrer\"", "<span class=\"lc-thumb\"><img src=\"/uploads/",
                "<strong class=\"lc-title\">봄날 &lt;b&gt;라이브&lt;/b&gt;</strong>",
                "<span class=\"lc-site\">음악 사이트</span>", "설명입니다");
        assertThat(html).doesNotContain("<script", "<b>");
        assertThat(html).contains("노래는 <a href=\"" + base + "/song\" rel=\"nofollow noopener noreferrer\">여기</a>");

        // 썸네일은 이 사이트에 저장한 사진이라 바로 열린다
        String thumb = html.replaceAll("(?s).*<img src=\"(/uploads/[^\"]+)\".*", "$1");
        getAs(thumb).andExpect(status().isOk());

        // 같은 링크는 다시 받지 않는다
        html(base + "/song");
        assertThat(pageHits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("사진이 아닌 썸네일은 저장하지 않고, 못 받은 링크는 글자 링크로 둔다")
    void failures() throws Exception {
        String html = html(base + "/svg-page\\n\\n" + base + "/missing");
        assertThat(html).contains("<strong class=\"lc-title\">사진 없는 카드</strong>").doesNotContain("lc-thumb", "<svg");
        assertThat(html).contains("<p><a href=\"" + base + "/missing\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM images", Long.class)).isZero();
    }

    @Test
    @DisplayName("내부망 주소는 받지 않는다 (SSRF)")
    void privateHostsBlocked() {
        SafeFetcher strict = new SafeFetcher(
                new LinkPreviewProperties(true, Duration.ofSeconds(1), 5, Duration.ofDays(7), false));
        for (String url : new String[] {base + "/song", "http://localhost/", "http://10.0.0.1/", "http://169.254.169.254/",
                "http://[::1]/", "http://192.168.0.1/", "file:///etc/passwd", "http://user@example.com/",
                "http://example.com:8080/", "http://[64:ff9b::a9fe:a9fe]/", "http://[64:ff9b::7f00:1]/",
                "http://[2002:c0a8:0001::1]/", "http://[2002:0a00:0001::1]/"}) {
            assertThat(strict.allowed(URI.create(url))).as(url).isFalse();
        }
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
