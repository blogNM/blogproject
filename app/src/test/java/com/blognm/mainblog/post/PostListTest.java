package com.blognm.mainblog.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 004 User Story 3 - 목록·카테고리·태그 (BRD-02·03·04) */
class PostListTest extends IntegrationTest {

    private Cookie[] owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        postAs("/api/blogs", """
                {"slug":"food","name":"맛집","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
    }

    private long category(String name) throws Exception {
        String body = postAs("/api/blogs/food/categories", "{\"name\":\"%s\"}".formatted(name), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void write(String title, Long categoryId, String tag, boolean notice) throws Exception {
        postAs("/api/blogs/food/posts", """
                {"title":"%s","content":"본문","categoryId":%s,"tags":[%s],"notice":%s}""".formatted(title,
                categoryId, tag == null ? "" : "\"" + tag + "\"", notice), owner).andExpect(status().isOk());
    }

    @Test
    @DisplayName("최신순 10개가 번호 페이지로 나오고 20·30개로 바꿀 수 있다. 공지는 위에 따로 (D-06, D-76)")
    void paging() throws Exception {
        for (int i = 1; i <= 25; i++) {
            write("글 " + i, null, null, false);
            clock.advance(java.time.Duration.ofSeconds(1));
        }
        write("공지 글", null, null, true);
        getAs("/api/blogs/food/posts")
                .andExpect(jsonPath("$.notices.length()").value(1))
                .andExpect(jsonPath("$.notices[0].title").value("공지 글"))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].title").value("글 25"))
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.totalPages").value(3));
        getAs("/api/blogs/food/posts?page=3").andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.notices.length()").value(0));
        getAs("/api/blogs/food/posts?size=20").andExpect(jsonPath("$.items.length()").value(20));
        getAs("/api/blogs/food/posts?size=30").andExpect(jsonPath("$.items.length()").value(25));
        getAs("/api/blogs/food/posts?size=999").andExpect(jsonPath("$.size").value(10));
    }

    @Test
    @DisplayName("카테고리·태그로 모아 본다. 태그를 누르면 같은 태그의 글만 나온다")
    void filters() throws Exception {
        long korean = category("한식");
        long cafe = category("카페");
        write("국밥", korean, "서울", false);
        write("냉면", korean, "평양", false);
        write("라떼", cafe, "서울", false);

        getAs("/api/blogs/food/posts?category=" + korean).andExpect(jsonPath("$.items.length()").value(2));
        getAs("/api/blogs/food/posts?tag=서울").andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.tag").value("서울"));
        getAs("/api/blogs/food/categories")
                .andExpect(jsonPath("$[0].name").value("한식"))
                .andExpect(jsonPath("$[0].postCount").value(2))
                .andExpect(jsonPath("$[1].postCount").value(1));
    }

    @Test
    @DisplayName("카테고리는 블로그장만 관리한다. 지우면 그 글은 분류 없음이 된다")
    void categoryManagement() throws Exception {
        long cafe = category("카페");
        write("라떼", cafe, null, false);
        postAs("/api/blogs/food/categories", "{\"name\":\"카페\"}", owner)
                .andExpect(jsonPath("$.code").value("CATEGORY_EXISTS"));
        postAs("/api/blogs/food/categories", "{\"name\":\"%s\"}".formatted("가".repeat(21)), owner)
                .andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));

        Cookie[] member = member("m@example.com", "멤버");
        postAs("/api/blogs/food/join", null, member).andExpect(status().isOk());
        postAs("/api/blogs/food/categories", "{\"name\":\"몰래\"}", member).andExpect(status().isForbidden());
        sendAs(HttpMethod.DELETE, "/api/blogs/food/categories/" + cafe, null, member).andExpect(status().isForbidden());

        sendAs(HttpMethod.PUT, "/api/blogs/food/categories/" + cafe, "{\"name\":\"커피\"}", owner)
                .andExpect(status().isOk());
        getAs("/api/blogs/food/posts").andExpect(jsonPath("$.items[0].categoryName").value("커피"));
        sendAs(HttpMethod.DELETE, "/api/blogs/food/categories/" + cafe, null, owner).andExpect(status().isOk());
        getAs("/api/blogs/food/posts").andExpect(jsonPath("$.items[0].title").value("라떼"))
                .andExpect(jsonPath("$.items[0].categoryName").doesNotExist());
    }

    @Test
    @DisplayName("다른 블로그의 카테고리로는 글을 쓸 수 없다")
    void foreignCategory() throws Exception {
        Cookie[] other = member("other@example.com", "다른");
        postAs("/api/blogs", """
                {"slug":"other","name":"다른","visibility":"PUBLIC","joinPolicy":"FREE"}""", other)
                .andExpect(status().isOk());
        String body = postAs("/api/blogs/other/categories", "{\"name\":\"남의 것\"}", other)
                .andReturn().getResponse().getContentAsString();
        long foreign = ((Number) JsonPath.read(body, "$.id")).longValue();
        postAs("/api/blogs/food/posts", """
                {"title":"글","content":"본문","categoryId":%d}""".formatted(foreign), owner)
                .andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));
    }
}
