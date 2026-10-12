package com.blognm.mainblog.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.admin.AdminAccountService;
import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 메인 공지사항: 관리자가 쓰고, 누구나 읽고, 회원에게 알림이 간다 (BRD-10, SOC-04). */
class NoticeTest extends IntegrationTest {

    @Autowired
    AdminAccountService accounts;

    private Cookie admin;

    @BeforeEach
    void setUp() throws Exception {
        accounts.run(null);
        Cookie temp = postJson("/api/admin/auth/login", """
                {"loginId":"rootadmin","password":"Temp1234!"}""").andReturn().getResponse().getCookie("ADMIN_TOKEN");
        admin = postAs("/api/admin/auth/password", """
                {"currentPassword":"Temp1234!","newPassword":"Admin5678!"}""", temp)
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("ADMIN_TOKEN");
    }

    private int notices(String email) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n JOIN members m ON m.id = n.recipient_id
                WHERE m.email = ? AND n.type = 'NOTICE'""", Integer.class, email);
    }

    @Test
    @DisplayName("관리자가 공지를 쓰면 알림을 끄지 않은 회원에게 알림이 가고, 본문은 걸러진 HTML로 나간다")
    void writeAndRead() throws Exception {
        Cookie[] a = member("a@example.com", "회원에이");
        Cookie[] b = member("b@example.com", "회원비");
        sendAs(HttpMethod.PUT, "/api/me/notification-settings", """
                {"enabled":{"NOTICE":false}}""", b).andExpect(status().isOk());

        // 회원 로그인으로는 못 쓴다
        postAs("/api/admin/notices", """
                {"title":"공지","content":"내용"}""", a).andExpect(status().isUnauthorized());
        postAs("/api/admin/notices", """
                {"title":"","content":"내용"}""", admin).andExpect(status().isBadRequest());

        String res = postAs("/api/admin/notices", """
                {"title":"점검 안내","content":"**토요일** 점검 <script>alert(1)</script>"}""", admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(res, "$.id")).longValue();

        assertThat(notices("a@example.com")).isEqualTo(1);
        assertThat(notices("b@example.com")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_logs WHERE action = 'NOTICE'", Integer.class))
                .isEqualTo(1);

        // 비회원도 목록·본문을 본다
        getAs("/api/notices").andExpect(jsonPath("$[0].id").value(id));
        getAs("/api/notices/all").andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("점검 안내"))
                .andExpect(jsonPath("$.items[0].category").value("NOTICE"));
        String detail = getAs("/api/notices/" + id).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String html = JsonPath.read(detail, "$.html");
        assertThat(html).contains("<strong>토요일</strong>").doesNotContain("<script");
        getAs("/notices/" + id).andExpect(status().isOk());

        // 고쳐도 알림은 다시 안 간다
        sendAs(HttpMethod.PUT, "/api/admin/notices/" + id, """
                {"title":"점검 안내 (바뀜)","content":"일요일로 바뀌었습니다","category":"UPDATE"}""", admin).andExpect(status().isOk());
        getAs("/api/notices/" + id).andExpect(jsonPath("$.title").value("점검 안내 (바뀜)"))
                .andExpect(jsonPath("$.category").value("UPDATE"));
        assertThat(notices("a@example.com")).isEqualTo(1);

        sendAs(HttpMethod.DELETE, "/api/admin/notices/" + id, null, admin).andExpect(status().isOk());
        getAs("/api/notices/" + id).andExpect(status().isNotFound());
        getAs("/api/notices/all").andExpect(jsonPath("$.total").value(0));
    }
}
