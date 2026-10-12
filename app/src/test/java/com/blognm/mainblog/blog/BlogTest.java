package com.blognm.mainblog.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;

import com.blognm.mainblog.support.IntegrationTest;

/** 002 User Story 1 - 블로그 만들기, BLG-02·03 목록·검색 */
class BlogTest extends IntegrationTest {

    private Cookie[] owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
    }

    private ResultActions create(Cookie[] who, String slug, String visibility) throws Exception {
        return postAs("/api/blogs", """
                {"slug":"%s","name":"%s 블로그","description":"%s 소개","visibility":"%s","joinPolicy":"FREE",
                 "tags":["여행","Java"]}""".formatted(slug, slug, slug, visibility), who);
    }

    @Test
    @DisplayName("블로그를 만들면 /blog/{주소}로 열리고 만든 회원이 블로그장이다")
    void createAndView() throws Exception {
        create(owner, "Travel-Log", "PUBLIC").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("travel-log"));

        getAs("/api/blogs/travel-log", owner)
                .andExpect(jsonPath("$.name").value("Travel-Log 블로그"))
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.tags[0]").value("java"))
                .andExpect(jsonPath("$.tags[1]").value("여행"));
        getAs("/api/me/blogs", owner).andExpect(jsonPath("$.owned[0].slug").value("travel-log"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/blog/travel-log"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("/js/pages/blog.js")));
    }

    @Test
    @DisplayName("이미 쓰이는 주소, 예약어, 형식에 맞지 않는 주소는 거절한다")
    void slugRules() throws Exception {
        create(owner, "taken", "PRIVATE").andExpect(status().isOk());
        Cookie[] other = member("other@example.com", "다른회원");
        create(other, "taken", "PUBLIC").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
        create(other, "admin", "PUBLIC").andExpect(jsonPath("$.code").value("RESERVED_SLUG"));
        create(other, "ab", "PUBLIC").andExpect(jsonPath("$.code").value("INVALID_SLUG"));
        create(other, "한글주소", "PUBLIC").andExpect(jsonPath("$.code").value("INVALID_SLUG"));
        getAs("/api/blog-slug-available?slug=taken", other).andExpect(jsonPath("$.available").value(false));
        getAs("/api/blog-slug-available?slug=free-one", other).andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("이름 1~30자, 소개 200자, 태그 5개까지")
    void inputRules() throws Exception {
        postAs("/api/blogs", """
                {"slug":"aaa","name":"  ","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(jsonPath("$.code").value("INVALID_BLOG_NAME"));
        postAs("/api/blogs", """
                {"slug":"aaa","name":"%s","visibility":"PUBLIC","joinPolicy":"FREE"}""".formatted("가".repeat(31)), owner)
                .andExpect(jsonPath("$.code").value("INVALID_BLOG_NAME"));
        postAs("/api/blogs", """
                {"slug":"aaa","name":"이름","description":"%s","visibility":"PUBLIC","joinPolicy":"FREE"}"""
                .formatted("가".repeat(201)), owner).andExpect(jsonPath("$.code").value("INVALID_BLOG_DESCRIPTION"));
        postAs("/api/blogs", """
                {"slug":"aaa","name":"이름","visibility":"PUBLIC","joinPolicy":"FREE","tags":["a","b","c","d","e","f"]}""",
                owner).andExpect(jsonPath("$.code").value("TOO_MANY_TAGS"));
        postAs("/api/blogs", """
                {"slug":"aaa","name":"이름","visibility":"PUBLIC"}""", owner).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("공개(일부 공개 포함)는 3개, 비공개는 5개까지 만든다")
    void limits() throws Exception {
        create(owner, "pub-1", "PUBLIC").andExpect(status().isOk());
        create(owner, "pub-2", "LINK_ONLY").andExpect(status().isOk());
        create(owner, "pub-3", "PUBLIC").andExpect(status().isOk());
        create(owner, "pub-4", "PUBLIC").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BLOG_LIMIT"));
        create(owner, "pub-5", "LINK_ONLY").andExpect(status().isConflict());
        for (int i = 1; i <= 5; i++) {
            create(owner, "priv-" + i, "PRIVATE").andExpect(status().isOk());
        }
        create(owner, "priv-6", "PRIVATE").andExpect(status().isConflict());
        // 비공개를 공개로 바꾸는 것도 같은 제한을 받는다
        sendAs(HttpMethod.PUT, "/api/blogs/priv-1", """
                {"name":"이름","visibility":"PUBLIC"}""", owner).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("공개 블로그 2개인 회원이 동시에 두 번 만들어도 하나만 만들어진다 (D-68)")
    void concurrentCreate() throws Exception {
        create(owner, "pub-1", "PUBLIC");
        create(owner, "pub-2", "PUBLIC");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String slug = "race-" + i;
            results.add(pool.submit(() -> {
                start.await();
                return create(owner, slug, "PUBLIC").andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE visibility = 'PUBLIC'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("목록·검색에는 공개 블로그만 나온다 (SC-004)")
    void listOnlyPublic() throws Exception {
        create(owner, "open-blog", "PUBLIC");
        create(owner, "link-blog", "LINK_ONLY");
        create(owner, "secret-blog", "PRIVATE");

        getAs("/api/blogs").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("open-blog"))
                .andExpect(jsonPath("$.items[0].ownerNickname").value("블로그장"));
        getAs("/api/blogs?q=블로그").andExpect(jsonPath("$.total").value(1));
        getAs("/api/blogs?q=여행").andExpect(jsonPath("$.total").value(1));
        getAs("/api/blogs?q=secret").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("이름·소개·태그로 검색하고, 최신순·인기순으로 페이지를 나눈다")
    void searchAndSort() throws Exception {
        postAs("/api/blogs", """
                {"slug":"cook","name":"요리 일기","description":"집밥 레시피","visibility":"PUBLIC","joinPolicy":"FREE",
                 "tags":["요리"]}""", owner);
        Cookie[] b = member("b@example.com", "회원비");
        postAs("/api/blogs", """
                {"slug":"camp","name":"캠핑 기록","description":"주말 캠핑","visibility":"PUBLIC","joinPolicy":"FREE",
                 "tags":["캠핑","여행"]}""", b);

        getAs("/api/blogs?q=요리").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].slug").value("cook"));
        getAs("/api/blogs?q=레시피").andExpect(jsonPath("$.items[0].slug").value("cook"));
        getAs("/api/blogs?q=여행").andExpect(jsonPath("$.items[0].slug").value("camp"));
        getAs("/api/blogs?q=캠").andExpect(jsonPath("$.items[0].slug").value("camp"));
        getAs("/api/blogs").andExpect(jsonPath("$.items[0].slug").value("camp"));

        long ownerId = jdbc.queryForObject("SELECT id FROM members WHERE nickname = '블로그장'", Long.class);
        long cookId = jdbc.queryForObject("SELECT id FROM blogs WHERE slug = 'cook'", Long.class);
        jdbc.update("INSERT INTO subscriptions (member_id, blog_id) VALUES (?, ?)", ownerId, cookId);
        getAs("/api/blogs?sort=popular").andExpect(jsonPath("$.items[0].slug").value("cook"));
    }

    @Test
    @DisplayName("일부 공개는 공유 링크로만 보이고, 링크를 새로 만들면 이전 링크는 막힌다")
    void linkOnly() throws Exception {
        create(owner, "link-blog", "LINK_ONLY");
        String key = com.jayway.jsonpath.JsonPath.read(
                getAs("/api/blogs/link-blog", owner).andReturn().getResponse().getContentAsString(), "$.shareToken");
        assertThat(key).hasSize(32);

        getAs("/api/blogs/link-blog").andExpect(status().isNotFound());
        getAs("/api/blogs/link-blog?key=" + key).andExpect(status().isOk())
                .andExpect(jsonPath("$.shareToken").doesNotExist());

        postAs("/api/blogs/link-blog/share-link", null, owner).andExpect(status().isOk());
        getAs("/api/blogs/link-blog?key=" + key).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("비공개 블로그는 멤버가 아니면 있는지도 알 수 없다")
    void privateHidden() throws Exception {
        create(owner, "secret", "PRIVATE");
        Cookie[] other = member("other@example.com", "다른회원");
        getAs("/api/blogs/secret", other).andExpect(status().isNotFound());
        getAs("/api/blogs/secret").andExpect(status().isNotFound());
        postAs("/api/blogs/secret/join", null, other).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("부블로그장 권한이 없는 멤버는 블로그 정보를 바꿀 수 없다")
    void onlyEditorUpdates() throws Exception {
        create(owner, "team", "PUBLIC");
        Cookie[] m = member("m@example.com", "멤버");
        postAs("/api/blogs/team/join", null, m).andExpect(status().isOk());
        sendAs(HttpMethod.PUT, "/api/blogs/team", "{\"name\":\"바꿈\"}", m).andExpect(status().isForbidden());

        sendAs(HttpMethod.PUT, "/api/blogs/team", """
                {"name":"새 이름","description":"새 소개","tags":["새태그"],"joinPolicy":"APPROVAL"}""", owner)
                .andExpect(status().isOk());
        getAs("/api/blogs/team").andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.joinPolicy").value("APPROVAL"))
                .andExpect(jsonPath("$.tags.length()").value(1));
    }
}
