package com.blognm.mainblog.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 004 User Story 5(좋아요)·6(통합 검색)·7(메인 피드)·8(조회수) */
class BoardTest extends IntegrationTest {

    private Cookie[] owner;
    private Cookie[] reader;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        reader = member("reader@example.com", "독자");
        postAs("/api/blogs", """
                {"slug":"food","name":"서울 맛집 탐방","description":"을지로 노포","visibility":"PUBLIC","joinPolicy":"FREE"}""",
                owner).andExpect(status().isOk());
        postAs("/api/blogs", """
                {"slug":"secret","name":"비밀 맛집","visibility":"PRIVATE","joinPolicy":"APPROVAL"}""", owner)
                .andExpect(status().isOk());
    }

    private long write(String slug, String title, String... tags) throws Exception {
        String tagJson = tags.length == 0 ? "" : "\"" + String.join("\",\"", tags) + "\"";
        return id(postAs("/api/blogs/" + slug + "/posts", """
                {"title":"%s","content":"본문","tags":[%s]}""".formatted(title, tagJson), owner));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.id")).longValue();
    }

    @Test
    @DisplayName("좋아요는 누르면 켜지고 다시 누르면 꺼진다. 알림은 같은 사람·같은 글 하루 한 번 (US5-4, 추천안 #11)")
    void likeToggle() throws Exception {
        long post = write("food", "국밥");
        postAs("/api/posts/" + post + "/like", null, reader)
                .andExpect(jsonPath("$.liked").value(true)).andExpect(jsonPath("$.likes").value(1));
        getAs("/api/posts/" + post, reader).andExpect(jsonPath("$.liked").value(true));
        postAs("/api/posts/" + post + "/like", null, reader)
                .andExpect(jsonPath("$.liked").value(false)).andExpect(jsonPath("$.likes").value(0));
        postAs("/api/posts/" + post + "/like", null, reader).andExpect(jsonPath("$.likes").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'LIKE'", Integer.class))
                .isEqualTo(1);
        postAs("/api/posts/" + post + "/like", null).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("같은 사람이 같은 날 여러 번 열어도 조회수는 1. 비회원은 IP로 센다 (SC-004, 추천안 #13)")
    void viewCountedOncePerDay() throws Exception {
        long post = write("food", "국밥");
        for (int i = 0; i < 3; i++) {
            getAs("/api/posts/" + post, reader);
            mvc.perform(MockMvcRequestBuilders.get("/api/posts/" + post).with(r -> {
                r.setRemoteAddr("203.0.113.7");
                return r;
            }));
        }
        getAs("/api/posts/" + post, owner).andExpect(jsonPath("$.counts.views").value(3));
        clock.advance(Duration.ofDays(1));
        getAs("/api/posts/" + post, reader).andExpect(jsonPath("$.counts.views").value(4));
        String key = jdbc.queryForObject("SELECT viewer_key FROM post_views WHERE viewer_key LIKE 'ip:%' LIMIT 1",
                String.class);
        assertThat(key).doesNotContain("203.0.113.7");
    }

    @Test
    @DisplayName("두 글자로 글 제목·블로그를 찾고, 비공개 블로그 글은 나오지 않는다 (US6)")
    void searchExcludesPrivate() throws Exception {
        write("food", "을지로 국밥집", "국밥");
        write("food", "망원동 떡볶이");
        write("secret", "비밀 국밥집", "국밥");

        getAs("/api/search?q=국밥")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.POST").value(1))
                .andExpect(jsonPath("$.counts.TAG").value(1))
                .andExpect(jsonPath("$.posts.items[0].title").value("을지로 국밥집"))
                .andExpect(jsonPath("$.posts.items[0].blogSlug").value("food"));
        getAs("/api/search?q=맛집&type=blog")
                .andExpect(jsonPath("$.counts.BLOG").value(1))
                .andExpect(jsonPath("$.blogs.items[0].slug").value("food"));
        getAs("/api/search?q=블로그장&type=nickname").andExpect(jsonPath("$.posts.total").value(2));
        getAs("/api/search?q=없는검색어").andExpect(jsonPath("$.posts.total").value(0));
    }

    @Test
    @DisplayName("1글자·21자·특수문자만 있는 검색어는 막힌다 (US6-1)")
    void invalidQueries() throws Exception {
        getAs("/api/search?q=국").andExpect(status().isBadRequest());
        getAs("/api/search?q=" + "가".repeat(21)).andExpect(status().isBadRequest());
        getAs("/api/search?q=!!**").andExpect(status().isBadRequest());
        getAs("/api/search?q=@@~~").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("차단한 회원의 글은 검색·피드에 나오지 않는다 (SC-005)")
    void blockedHidden() throws Exception {
        write("food", "을지로 국밥집");
        jdbc.update("""
                INSERT INTO blocks (blocker_id, blocked_id)
                SELECT r.id, o.id FROM members r, members o WHERE r.nickname = '독자' AND o.nickname = '블로그장'""");
        getAs("/api/search?q=국밥", reader).andExpect(jsonPath("$.counts.POST").value(0));
        getAs("/api/feed", reader).andExpect(jsonPath("$.total").value(0));
        getAs("/api/feed").andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("최근 검색어는 본인만, 10개까지, 하나씩·전체 지우기 (6.1)")
    void recentSearches() throws Exception {
        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofSeconds(1));
            getAs("/api/search?q=검색" + i, reader).andExpect(status().isOk());
        }
        String body = getAs("/api/me/recent-searches", reader)
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].keyword").value("검색11"))
                .andReturn().getResponse().getContentAsString();
        long first = ((Number) JsonPath.read(body, "$[0].id")).longValue();
        getAs("/api/me/recent-searches", owner).andExpect(jsonPath("$.length()").value(0));
        sendAs(HttpMethod.DELETE, "/api/me/recent-searches/" + first, null, reader).andExpect(status().isOk());
        getAs("/api/me/recent-searches", reader).andExpect(jsonPath("$.length()").value(9));
        sendAs(HttpMethod.DELETE, "/api/me/recent-searches", null, reader).andExpect(status().isOk());
        getAs("/api/me/recent-searches", reader).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("피드: 지운 글은 다음 조회에서 빠지고, 인기 탭은 최근 7일 좋아요 순, 구독·팔로우 탭은 따로 (US7)")
    void feed() throws Exception {
        long old = write("food", "오래된 글");
        jdbc.update("UPDATE posts SET created_at = created_at - INTERVAL 8 DAY WHERE id = ?", old);
        long a = write("food", "새 글 A");
        long b = write("food", "새 글 B");
        write("secret", "비밀 글");
        postAs("/api/posts/" + a + "/like", null, reader);
        postAs("/api/posts/" + old + "/like", null, reader);

        getAs("/api/feed").andExpect(jsonPath("$.total").value(3));
        getAs("/api/feed?tab=popular")
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].title").value("새 글 A"));
        sendAs(HttpMethod.DELETE, "/api/posts/" + b, null, owner).andExpect(status().isOk());
        getAs("/api/feed").andExpect(jsonPath("$.total").value(2));

        getAs("/api/feed?tab=subscribed").andExpect(status().isUnauthorized());
        getAs("/api/feed?tab=subscribed", reader).andExpect(jsonPath("$.total").value(0));
        jdbc.update("""
                INSERT INTO subscriptions (member_id, blog_id)
                SELECT m.id, b.id FROM members m, blogs b WHERE m.nickname = '독자' AND b.slug = 'food'""");
        getAs("/api/feed?tab=subscribed", reader).andExpect(jsonPath("$.total").value(2));
        getAs("/api/feed?tab=following", reader).andExpect(jsonPath("$.total").value(0));
        jdbc.update("""
                INSERT INTO follows (follower_id, followee_id)
                SELECT r.id, o.id FROM members r, members o WHERE r.nickname = '독자' AND o.nickname = '블로그장'""");
        getAs("/api/feed?tab=following", reader).andExpect(jsonPath("$.total").value(2));
    }
}
