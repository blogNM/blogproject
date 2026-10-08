package com.blognm.mainblog.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 메인 관리자 로그인과 조치 (005, SEC-09, 추천안 #16·#17). */
class AdminTest extends IntegrationTest {

    @Autowired
    AdminAccountService accounts;

    private Cookie[] owner;
    private long ownerId;

    @BeforeEach
    void setUp() throws Exception {
        accounts.run(null);
        owner = member("owner@example.com", "블로그장");
        ownerId = jdbc.queryForObject("SELECT id FROM members WHERE email = 'owner@example.com'", Long.class);
        postAs("/api/blogs", """
                {"slug":"food","name":"맛집","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner).andExpect(status().isOk());
    }

    private Cookie adminLogin(String password) throws Exception {
        return postJson("/api/admin/auth/login", """
                {"loginId":"rootadmin","password":"%s"}""".formatted(password))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("ADMIN_TOKEN");
    }

    /** 임시 비밀번호로 들어와 바꾼 뒤의 관리자 쿠키. */
    private Cookie readyAdmin() throws Exception {
        Cookie temp = adminLogin("Temp1234!");
        return postAs("/api/admin/auth/password", """
                {"currentPassword":"Temp1234!","newPassword":"Admin5678!"}""", temp)
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("ADMIN_TOKEN");
    }

    private int logs(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_logs WHERE action = ?", Integer.class, action);
    }

    @Test
    @DisplayName("첫 관리자는 환경변수로 만들고, 비밀번호를 바꾸기 전에는 관리 기능을 못 쓴다 (추천안 #16)")
    void firstLoginMustChangePassword() throws Exception {
        postJson("/api/admin/auth/login", """
                {"loginId":"rootadmin","password":"wrong"}""").andExpect(status().isUnauthorized());
        Cookie temp = adminLogin("Temp1234!");
        getAs("/api/admin/auth/me", temp).andExpect(jsonPath("$.mustChangePassword").value(true));
        getAs("/api/admin/stats", temp).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        clock.advance(Duration.ofSeconds(2));
        Cookie ready = postAs("/api/admin/auth/password", """
                {"currentPassword":"Temp1234!","newPassword":"Admin5678!"}""", temp)
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("ADMIN_TOKEN");
        getAs("/api/admin/stats", ready).andExpect(status().isOk()).andExpect(jsonPath("$.members").value(1));
        // 바꾸기 전에 받은 토큰은 더 이상 안 된다
        getAs("/api/admin/stats", temp).andExpect(status().isUnauthorized());
        // 이미 관리자가 있으면 다시 만들지 않는다
        accounts.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admins", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("회원 로그인으로는 관리자 주소에 못 들어가고, 관리자 로그인으로는 회원 기능을 못 쓴다 (SEC-09)")
    void separatedFromMembers() throws Exception {
        getAs("/api/admin/stats", owner).andExpect(status().isUnauthorized());
        getAs("/admin/index.html", owner).andExpect(status().is3xxRedirection());
        Cookie admin = readyAdmin();
        postAs("/api/blogs/food/posts", """
                {"title":"관리자 글","content":"본문","tags":[]}""", admin).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("개인정보는 가려서 보이고, 전체 보기를 누르면 기록이 남는다 (ADM-06)")
    void revealIsLogged() throws Exception {
        Cookie admin = readyAdmin();
        getAs("/api/admin/members/" + ownerId, admin).andExpect(jsonPath("$.email").value("ow***@example.com"))
                .andExpect(jsonPath("$.name").value("김***")).andExpect(jsonPath("$.blogs[0].role").value("OWNER"));
        postAs("/api/admin/members/" + ownerId + "/reveal", null, admin)
                .andExpect(jsonPath("$.email").value("owner@example.com"));
        assertThat(logs("VIEW_PII")).isEqualTo(1);
    }

    @Test
    @DisplayName("숨긴 블로그는 멤버가 아니면 못 보고, 숨김을 풀 수 있다 (추천안 #17)")
    void hideBlog() throws Exception {
        Cookie admin = readyAdmin();
        Cookie[] reader = member("reader@example.com", "독자");
        long blogId = jdbc.queryForObject("SELECT id FROM blogs WHERE slug = 'food'", Long.class);
        postAs("/api/admin/blogs/" + blogId + "/hide", "{\"reason\":\"스팸\"}", admin).andExpect(status().isOk());
        getAs("/api/blogs/food", reader).andExpect(status().isNotFound());
        getAs("/api/blogs/food", owner).andExpect(status().isOk());
        postAs("/api/admin/blogs/" + blogId + "/unhide", null, admin).andExpect(status().isOk());
        getAs("/api/blogs/food", reader).andExpect(status().isOk());
        assertThat(logs("HIDE")).isEqualTo(1);
        assertThat(logs("UNHIDE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND type = 'BLOG_HIDDEN'",
                Integer.class, ownerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("블로그장 글 신고로 경고가 3번 쌓이면 가장 먼저 된 부블로그장이 블로그장이 된다 (ADM-07)")
    void ownerWarningsTransfer() throws Exception {
        Cookie admin;
        readyAdmin();
        Cookie[] sub = member("sub@example.com", "부장");
        Cookie[] reader = member("reader@example.com", "독자");
        long subId = jdbc.queryForObject("SELECT id FROM members WHERE email = 'sub@example.com'", Long.class);
        postAs("/api/blogs/food/join", null, sub).andExpect(status().isOk());
        sendAs(HttpMethod.PUT, "/api/blogs/food/members/" + subId + "/role", """
                {"role":"SUB_OWNER","permissions":{"editInfo":false,"manageMembers":true,"managePosts":false}}""", owner)
                .andExpect(status().isOk());
        String r = postAs("/api/blogs/food/posts", """
                {"title":"블로그장 글","content":"본문","tags":[]}""", owner).andReturn().getResponse().getContentAsString();
        long postId = ((Number) JsonPath.read(r, "$.id")).longValue();
        for (int i = 1; i <= 3; i++) {
            reader = login("reader@example.com", "Abcd1234!");
            admin = adminLogin("Admin5678!");
            postAs("/api/reports", """
                    {"targetType":"POST","targetId":%d,"reason":"ABUSE"}""".formatted(postId), reader)
                    .andExpect(jsonPath("$.handler").value("ADMIN"));
            String list = getAs("/api/admin/reports", admin).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long reportId = ((Number) JsonPath.read(list, "$.items[0].id")).longValue();
            postAs("/api/admin/reports/" + reportId, """
                    {"result":"OWNER_WARNING","sanctionReason":"ABUSE","reason":"욕설"}""", admin).andExpect(status().isOk());
            clock.advance(Duration.ofDays(15));
        }
        assertThat(jdbc.queryForObject("SELECT owner_id FROM blogs WHERE slug = 'food'", Long.class)).isEqualTo(subId);
        assertThat(logs("REPORT")).isEqualTo(3);
    }

    @Test
    @DisplayName("관리자가 지운 글은 30일 안에 되살릴 수 있다 (ADM-03)")
    void deleteAndRestorePost() throws Exception {
        Cookie admin = readyAdmin();
        String r = postAs("/api/blogs/food/posts", """
                {"title":"문제 글","content":"본문","tags":[]}""", owner).andReturn().getResponse().getContentAsString();
        long postId = ((Number) JsonPath.read(r, "$.id")).longValue();
        postAs("/api/admin/posts/" + postId + "/delete", "{\"reason\":\"규정 위반\"}", admin).andExpect(status().isOk());
        getAs("/api/posts/" + postId).andExpect(status().isNotFound());
        getAs("/api/admin/posts?status=DELETED", admin).andExpect(jsonPath("$.items[0].deletedBy").value("ADMIN"));
        postAs("/api/admin/posts/" + postId + "/restore", null, admin).andExpect(status().isOk());
        getAs("/api/posts/" + postId).andExpect(status().isOk());
        assertThat(logs("DELETE")).isEqualTo(1);
        assertThat(logs("RESTORE")).isEqualTo(1);
    }
}
