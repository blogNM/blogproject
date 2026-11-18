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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.blognm.mainblog.image.ImageService;
import com.blognm.mainblog.image.UploadProperties;
import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

/** 글 속 링크 미리보기 카드 (D-92). 이 컴퓨터에 띄운 가짜 사이트에서 받는다. */
class LinkPreviewTest extends IntegrationTest {

    private HttpServer site;
    private String base;
    private final AtomicInteger pageHits = new AtomicInteger();
    private final AtomicInteger slowHits = new AtomicInteger();
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
        // 머리는 바로 보내고 본문을 아주 천천히 보내는 사이트 (D-128 시간 상한 확인용)
        site.createContext("/slow/", ex -> {
            slowHits.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 0);
            try {
                ex.getResponseBody().write("<html><head>".getBytes(StandardCharsets.UTF_8));
                ex.getResponseBody().flush();
                Thread.sleep(3000);
            } catch (InterruptedException | java.io.IOException e) {
                // 받는 쪽이 끊으면 여기로 온다
            }
            ex.close();
        });
        site.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
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

    @Autowired
    NamedParameterJdbcTemplate named;
    @Autowired
    ImageService images;
    @Autowired
    UploadProperties uploadProps;

    @Test
    @DisplayName("저장 응답은 바깥 사이트를 기다리지 않고, 미리보기는 뒤에서 받는다 (D-131)")
    void background() {
        LinkPreviewProperties props = new LinkPreviewProperties(true, Duration.ofSeconds(1), 5, Duration.ofDays(7),
                true, Duration.ofSeconds(8), true, Duration.ofDays(30));
        List<Runnable> queued = new ArrayList<>();
        LinkPreviewService service = new LinkPreviewService(named, new SafeFetcher(props), images, uploadProps, props,
                clock, queued::add);
        long ownerId = jdbc.queryForObject("SELECT id FROM members WHERE email = 'owner@example.com'", Long.class);

        service.collectAfterSave("<p><a href=\"" + base + "/song\">" + base + "/song</a></p>", ownerId);
        // 부른 자리에서는 바깥 사이트에 가지 않는다
        assertThat(pageHits.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM link_previews", Long.class)).isZero();

        queued.forEach(Runnable::run);
        assertThat(pageHits.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM link_previews", String.class)).isEqualTo("OK");
    }

    @Test
    @DisplayName("내부망 주소는 받지 않는다 (SSRF)")
    void privateHostsBlocked() {
        SafeFetcher strict = new SafeFetcher(
                new LinkPreviewProperties(true, Duration.ofSeconds(1), 5, Duration.ofDays(7), false,
                        Duration.ofSeconds(8), false, Duration.ofDays(30)));
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

    @Autowired
    com.blognm.mainblog.post.PostCleanupJob postCleanup;

    @Test
    @DisplayName("본문을 천천히 보내는 사이트는 한 번 1초, 글 하나 1.5초 상한에서 끊고 남은 링크는 받지 않는다 (D-128)")
    void slowSiteIsCut() {
        LinkPreviewProperties props = new LinkPreviewProperties(true, Duration.ofSeconds(1), 5, Duration.ofDays(7),
                true, Duration.ofMillis(1500), false, Duration.ofDays(30));
        LinkPreviewService service = new LinkPreviewService(named, new SafeFetcher(props), images, uploadProps, props,
                clock, Runnable::run);
        long ownerId = jdbc.queryForObject("SELECT id FROM members WHERE email = 'owner@example.com'", Long.class);
        StringBuilder html = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            html.append("<p><a href=\"").append(base).append("/slow/").append(i).append("\">링크</a></p>");
        }

        long start = System.nanoTime();
        service.collect(html.toString(), ownerId);
        long millis = (System.nanoTime() - start) / 1_000_000;

        // 1번은 1초에 끊기고, 1.5초 상한 안이라 2번을 받아 보다 또 1초에 끊기고, 3번은 상한을 넘어 받지 않는다
        assertThat(millis).isLessThan(2900);
        assertThat(slowHits.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM link_previews WHERE status = 'FAILED'", Long.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("어느 글에도 없는 링크의 미리보기는 30일 뒤 새벽 정리에서 썸네일과 함께 지운다. 글에 남은 링크는 그대로 (D-132)")
    void unusedPreviewsCleaned() throws Exception {
        String body = postAs("/api/blogs/music/posts", """
                {"title":"링크","content":"%s/song","tags":[]}""".formatted(base), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        html(base + "/svg-page");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM link_previews", Long.class)).isEqualTo(2);
        String thumb = jdbc.queryForObject(
                "SELECT i.stored_name FROM link_previews lp JOIN images i ON i.id = lp.image_id", String.class);

        // 글에서 링크를 빼도 30일 안에는 남긴다
        sendAs(org.springframework.http.HttpMethod.PUT, "/api/posts/" + id, """
                {"title":"링크","content":"링크를 뺐어요","tags":[]}""", owner).andExpect(status().isOk());
        clock.advance(Duration.ofDays(29));
        postCleanup.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM link_previews", Long.class)).isEqualTo(2);

        clock.advance(Duration.ofDays(2));
        postCleanup.cleanup();
        assertThat(jdbc.queryForList("SELECT url FROM link_previews", String.class))
                .containsExactly(base + "/svg-page");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM images WHERE stored_name = ?", Long.class, thumb))
                .isZero();
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(uploadProps.dir(), thumb))).isFalse();
    }

}
