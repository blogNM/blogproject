package com.blognm.mainblog.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 블로그 통계 (D-112): 방문 기록, 들어온 경로, 시간별·일별·월별 모아 세기, 볼 수 있는 사람, 보관 기간. */
class StatsTest extends IntegrationTest {

    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";

    @Autowired
    private VisitCleanupJob cleanupJob;

    private Cookie[] owner;
    private Cookie[] joined;
    private Cookie[] reader;
    private long blogId;
    private long postId;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        joined = member("joined@example.com", "멤버");
        reader = member("reader@example.com", "독자");
        postAs("/api/blogs", """
                {"slug":"food","name":"서울 맛집 탐방","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
        postAs("/api/blogs", """
                {"slug":"secret","name":"비밀 맛집","visibility":"PRIVATE","joinPolicy":"APPROVAL"}""", owner)
                .andExpect(status().isOk());
        postAs("/api/blogs/food/join", "{}", joined).andExpect(status().isOk());
        String body = postAs("/api/blogs/food/posts", """
                {"title":"을지로 국밥","content":"본문","tags":[]}""", owner).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        postId = ((Number) JsonPath.read(body, "$.id")).longValue();
        blogId = jdbc.queryForObject("SELECT id FROM blogs WHERE slug = 'food'", Long.class);
    }

    private ResultActions visit(String slug, String json, String ip, String userAgent, Cookie... cookies)
            throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.post("/api/blogs/" + slug + "/visits")
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(json)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        if (userAgent != null) {
            req.header("User-Agent", userAgent);
        }
        if (cookies.length > 0) {
            req.cookie(cookies);
        }
        return mvc.perform(req).andExpect(status().isNoContent());
    }

    private int visits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM blog_visits", Integer.class);
    }

    private Map<String, Object> last() {
        return jdbc.queryForMap("SELECT * FROM blog_visits ORDER BY id DESC LIMIT 1");
    }

    @Test
    @DisplayName("비회원·다른 회원의 방문은 남기고, 블로그 멤버·로봇·볼 수 없는 블로그는 세지 않는다. IP는 저장하지 않는다")
    void recordsOnlyRealVisitors() throws Exception {
        visit("food", "{}", "203.0.113.7", CHROME);
        assertThat(visits()).isEqualTo(1);
        Map<String, Object> row = last();
        assertThat(row.get("blog_id")).isEqualTo(blogId);
        assertThat(row.get("post_id")).isNull();
        assertThat((String) row.get("visitor_hash")).matches("[0-9a-f]{64}").doesNotContain("203.0.113.7");
        assertThat(row.get("source")).isEqualTo("direct");

        visit("food", "{\"postId\":%d}".formatted(postId), "203.0.113.8", CHROME, reader);
        assertThat(visits()).isEqualTo(2);
        assertThat(last().get("post_id")).isEqualTo(postId);

        // 블로그장·멤버 본인 방문, 로봇, 브라우저 정보 없음, 비공개 블로그, 다른 블로그의 글 번호
        visit("food", "{}", "203.0.113.9", CHROME, owner);
        visit("food", "{\"postId\":%d}".formatted(postId), "203.0.113.9", CHROME, joined);
        visit("food", "{}", "66.249.66.1", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        visit("food", "{}", "203.0.113.10", "Mozilla/5.0 HeadlessChrome/129.0");
        visit("food", "{}", "203.0.113.11", null);
        visit("secret", "{}", "203.0.113.12", CHROME, reader);
        visit("food", "{\"postId\":999999}", "203.0.113.13", CHROME);
        visit("nothing", "{}", "203.0.113.14", CHROME);
        assertThat(visits()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사람이 같은 화면을 30분 안에 다시 열면 한 번만 센다. 다른 화면이나 30분 뒤는 다시 센다")
    void repeatWindow() throws Exception {
        visit("food", "{}", "203.0.113.7", CHROME);
        visit("food", "{}", "203.0.113.7", CHROME);
        assertThat(visits()).isEqualTo(1);
        visit("food", "{\"postId\":%d}".formatted(postId), "203.0.113.7", CHROME);
        assertThat(visits()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(31));
        visit("food", "{}", "203.0.113.7", CHROME);
        assertThat(visits()).isEqualTo(3);
        // 다른 IP는 다른 방문자 값
        String before = (String) last().get("visitor_hash");
        visit("food", "{}", "203.0.113.99", CHROME);
        assertThat(last().get("visitor_hash")).isNotEqualTo(before);
    }

    @Test
    @DisplayName("들어온 경로: 링크 표시(from), 우리 사이트 이전 화면, 다른 사이트(호스트·검색어), 직접")
    void sources() throws Exception {
        String[][] cases = {
                {"{\"from\":\"search\",\"q\":\"  을지로   국밥 \"}", "search", null, "을지로 국밥"},
                {"{\"from\":\"popular\"}", "popular", null, null},
                {"{\"from\":\"explore\"}", "explore", null, null},
                {"{\"from\":\"new\"}", "new", null, null},
                {"{\"from\":\"comment\"}", "comment", null, null},
                {"{\"from\":\"feed\",\"q\":\"무시\"}", "feed", null, null},
                {"{\"from\":\"hacker\"}", "direct", null, null},
                {"{\"referrer\":\"http://localhost/search.html?q=%EB%83%89%EB%A9%B4&type=post\"}", "search", null, "냉면"},
                {"{\"referrer\":\"http://localhost/members/3\"}", "profile", null, null},
                {"{\"referrer\":\"http://localhost/blog/food/posts/1\"}", "inside", null, null},
                {"{\"referrer\":\"http://localhost/blog/foodie\"}", "site", null, null},
                {"{\"referrer\":\"http://localhost/\"}", "site", null, null},
                {"{\"referrer\":\"https://www.google.com/\"}", "external", "google.com", null},
                {"{\"referrer\":\"https://search.naver.com/search.naver?query=%EC%84%9C%EC%9A%B8+%EB%A7%9B%EC%A7%91\"}",
                        "external", "search.naver.com", "서울 맛집"},
                {"{\"referrer\":\"javascript:alert(1)\"}", "direct", null, null},
        };
        int ip = 1;
        for (String[] c : cases) {
            visit("food", c[0], "198.51.100." + ip++, CHROME);
            Map<String, Object> row = last();
            assertThat(row.get("source")).as(c[0]).isEqualTo(c[1]);
            assertThat(row.get("ref_host")).as(c[0]).isEqualTo(c[2]);
            assertThat(row.get("keyword")).as(c[0]).isEqualTo(c[3]);
        }
        assertThat(VisitSource.classify("search", "가".repeat(80), null, "food", Set.of("localhost"), 30).keyword())
                .hasSize(30);
        assertThat(VisitSource.classify("search", "!!! ???", null, "food", Set.of("localhost"), 30).keyword()).isNull();
    }

    private void insert(LocalDateTime at, String visitor, Long post, String source, String host, String keyword) {
        jdbc.update("""
                INSERT INTO blog_visits (blog_id, post_id, visited_at, visitor_hash, source, ref_host, keyword)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", blogId, post, at, Hashing.sha256Hex(visitor + at.toLocalDate()), source,
                host, keyword);
    }

    @Test
    @DisplayName("시간별·일별·월별로 방문자(하루 한 사람 한 번)와 조회수를 모으고, 경로·검색어·사이트·많이 본 글을 센다")
    void aggregates() throws Exception {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime t = today.atTime(9, 10);
        insert(t, "a", null, "search", null, "국밥");
        insert(t.plusMinutes(40), "a", postId, "inside", null, null);
        insert(t.plusHours(5), "b", postId, "search", null, "국밥");
        insert(t.plusHours(5), "c", postId, "external", "google.com", null);
        insert(t.minusDays(1), "a", null, "popular", null, null);
        insert(t.minusDays(1), "d", null, "direct", null, null);
        insert(t.minusMonths(2), "e", null, "explore", null, null);
        // 다른 블로그 기록은 섞이지 않는다
        jdbc.update("""
                INSERT INTO blog_visits (blog_id, post_id, visited_at, visitor_hash, source)
                VALUES (?, NULL, ?, ?, 'direct')""", blogId + 1000, t, "z".repeat(64));

        getAs("/api/blogs/food/stats?unit=hour&date=" + today, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(24))
                .andExpect(jsonPath("$.series[9].visitors").value(1)).andExpect(jsonPath("$.series[9].views").value(2))
                .andExpect(jsonPath("$.series[14].visitors").value(2)).andExpect(jsonPath("$.series[14].views").value(2))
                .andExpect(jsonPath("$.series[0].views").value(0))
                .andExpect(jsonPath("$.period.visitors").value(3)).andExpect(jsonPath("$.period.views").value(4))
                .andExpect(jsonPath("$.summary.today.views").value(4))
                .andExpect(jsonPath("$.summary.yesterday.visitors").value(2))
                .andExpect(jsonPath("$.summary.total.views").value(7))
                .andExpect(jsonPath("$.summary.keptDays").value(400))
                // 블로그 안에서 옮겨 다닌 것은 경로에서 빼고 따로 센다
                .andExpect(jsonPath("$.sources.total").value(3)).andExpect(jsonPath("$.sources.inside").value(1))
                .andExpect(jsonPath("$.sources.items[0].source").value("search"))
                .andExpect(jsonPath("$.sources.items[0].label").value("Creat_ 검색"))
                .andExpect(jsonPath("$.sources.items[0].percent").value(66.7))
                .andExpect(jsonPath("$.keywords[0].name").value("국밥")).andExpect(jsonPath("$.keywords[0].count").value(2))
                .andExpect(jsonPath("$.hosts[0].name").value("google.com"))
                .andExpect(jsonPath("$.posts[0].id").value(postId)).andExpect(jsonPath("$.posts[0].views").value(3))
                .andExpect(jsonPath("$.posts[0].title").value("을지로 국밥"));

        getAs("/api/blogs/food/stats?unit=day", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(30))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.series[29].visitors").value(3))
                .andExpect(jsonPath("$.series[28].visitors").value(2))
                // 기간 방문자는 날마다 센 방문자의 합 (a는 어제와 오늘 두 번)
                .andExpect(jsonPath("$.period.visitors").value(5));

        getAs("/api/blogs/food/stats?unit=month", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(12))
                .andExpect(jsonPath("$.series[11].key").value(today.toString().substring(0, 7)))
                .andExpect(jsonPath("$.series[9].views").value(1));

        getAs("/api/blogs/food/stats?unit=day&from=2026-01-01&to=2026-12-31", owner).andExpect(status().isBadRequest());
        getAs("/api/blogs/food/stats?unit=day&from=2026-10-10&to=2026-10-01", owner).andExpect(status().isBadRequest());
        getAs("/api/blogs/food/stats?unit=week", owner).andExpect(status().isBadRequest());
        getAs("/api/blogs/food/stats?unit=hour&date=어제", owner).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("통계는 블로그장·부블로그장만 본다. 일반 멤버·다른 회원·비회원은 못 본다")
    void whoCanSee() throws Exception {
        getAs("/api/blogs/food/stats", owner).andExpect(status().isOk());
        getAs("/api/blogs/food/stats", joined).andExpect(status().isForbidden());
        getAs("/api/blogs/food/stats", reader).andExpect(status().isForbidden());
        getAs("/api/blogs/food/stats").andExpect(status().isUnauthorized());
        jdbc.update("""
                UPDATE blog_memberships SET role = 'SUB_OWNER'
                WHERE member_id = (SELECT id FROM members WHERE email = 'joined@example.com')""");
        getAs("/api/blogs/food/stats", joined).andExpect(status().isOk());
    }

    @Test
    @DisplayName("보관 기간(400일)이 지난 방문 기록은 새벽 정리 작업이 지운다")
    void retention() {
        LocalDateTime now = LocalDateTime.now(clock);
        insert(now.minusDays(401), "a", null, "direct", null, null);
        insert(now.minusDays(399), "b", null, "direct", null, null);
        assertThat(cleanupJob.purge()).isEqualTo(1);
        assertThat(visits()).isEqualTo(1);
    }
}
