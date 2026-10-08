package com.blognm.mainblog.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.support.IntegrationTest;

/** 비공개 전환 알림(D-37), 블로그 태그 검색(BLG-03), 블로그 알림 일괄 삭제(6.7). */
class BlogExtrasTest extends IntegrationTest {

    private Cookie[] owner;
    private Cookie[] joined;
    private Cookie[] reader;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        joined = member("joined@example.com", "멤버");
        reader = member("reader@example.com", "구독자");
        postAs("/api/blogs", """
                {"slug":"food","name":"맛집 일지","visibility":"PUBLIC","joinPolicy":"FREE","tags":["국밥"]}""", owner)
                .andExpect(status().isOk());
        postAs("/api/blogs/food/join", "{}", joined).andExpect(status().isOk());
        postAs("/api/blogs/food/subscribe", null, joined).andExpect(status().isOk());
        postAs("/api/blogs/food/subscribe", null, reader).andExpect(status().isOk());
    }

    private int count(String email, String type) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n JOIN members m ON m.id = n.recipient_id
                WHERE m.email = ? AND n.type = ?""", Integer.class, email, type);
    }

    @Test
    @DisplayName("비공개로 바꾸면 멤버가 아닌 구독자에게만 알린다 (D-37)")
    void privateSwitchNotifiesSubscribers() throws Exception {
        sendAs(HttpMethod.PUT, "/api/blogs/food", """
                {"name":"맛집 일지","visibility":"LINK_ONLY"}""", owner).andExpect(status().isOk());
        assertThat(count("reader@example.com", "BLOG_PRIVATE")).isZero();
        sendAs(HttpMethod.PUT, "/api/blogs/food", """
                {"name":"맛집 일지","visibility":"PRIVATE"}""", owner).andExpect(status().isOk());
        assertThat(count("reader@example.com", "BLOG_PRIVATE")).isEqualTo(1);
        assertThat(count("joined@example.com", "BLOG_PRIVATE")).isZero();
        assertThat(count("owner@example.com", "BLOG_PRIVATE")).isZero();
        getAs("/api/notifications", reader).andExpect(jsonPath("$.items[0].message").value(
                org.hamcrest.Matchers.containsString("비공개로 바뀌었습니다")));
    }

    @Test
    @DisplayName("블로그 검색은 이름·소개뿐 아니라 블로그 태그로도 찾는다 (BLG-03)")
    void searchByBlogTag() throws Exception {
        getAs("/api/search?q=국밥&type=blog").andExpect(jsonPath("$.blogs.total").value(1))
                .andExpect(jsonPath("$.blogs.items[0].slug").value("food"));
        getAs("/api/search?q=맛집&type=blog").andExpect(jsonPath("$.blogs.total").value(1));
        getAs("/api/search?q=냉면&type=blog").andExpect(jsonPath("$.blogs.total").value(0));
    }

    @Test
    @DisplayName("블로그장은 이 블로그의 블로그 알림을 한 번에 지운다 (6.7)")
    void deleteBlogNotifications() throws Exception {
        sendAs(HttpMethod.PUT, "/api/blogs/food", """
                {"name":"맛집 일지","visibility":"PRIVATE"}""", owner).andExpect(status().isOk());
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE tab = 'BLOG'", Integer.class);
        assertThat(before).isPositive();
        sendAs(HttpMethod.DELETE, "/api/blogs/food/notifications", null, joined).andExpect(status().isForbidden());
        sendAs(HttpMethod.DELETE, "/api/blogs/food/notifications", null, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(before));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE tab = 'BLOG'", Integer.class)).isZero();
    }
}
