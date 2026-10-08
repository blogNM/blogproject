package com.blognm.mainblog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.blognm.mainblog.support.IntegrationTest;

/** 001 User Story 4 - 이메일 찾기 */
class EmailFindTest extends IntegrationTest {

    private static final String FIND = """
            {"name":" 홍길동 ","phone":"010-9999-8888"}""";

    @Test
    @DisplayName("이름·전화번호가 같은 계정을 모두 가려서 가입일과 함께 보여준다")
    void findsMasked() throws Exception {
        signup("abcdef@gmail.com", "Abcd1234!", "길동하나", "홍길동", "01099998888");
        signup("xy@naver.com", "Abcd1234!", "길동둘", "홍길동", "010 9999 8888");
        signup("other@gmail.com", "Abcd1234!", "다른사람", "홍길동", "01011112222");
        String today = LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));

        postJson("/api/auth/find-email", FIND)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].maskedEmail").value("ab***@gmail.com"))
                .andExpect(jsonPath("$.results[1].maskedEmail").value("x***@naver.com"))
                .andExpect(jsonPath("$.results[0].joinedAt").value(today));
    }

    @Test
    @DisplayName("일치하는 계정이 없으면 빈 목록이다")
    void noMatch() throws Exception {
        postJson("/api/auth/find-email", FIND).andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    @DisplayName("결과의 임시 토큰으로 인증번호를 받아 비밀번호를 재설정한다 (D-26)")
    void resetWithToken() throws Exception {
        signup("abcdef@gmail.com", "Abcd1234!", "길동하나", "홍길동", "01099998888");
        String body = postJson("/api/auth/find-email", FIND).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.results[0].resetToken");

        postJson("/api/auth/password/code", "{\"findToken\":\"%s\"}".formatted(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedEmail").value("ab***@gmail.com"));
        String code = waitForCode("abcdef@gmail.com");
        postJson("/api/auth/password/reset", """
                {"findToken":"%s","code":"%s","newPassword":"Newpw123!"}""".formatted(token, code))
                .andExpect(status().isOk());
        login("abcdef@gmail.com", "Newpw123!");
    }

    @Test
    @DisplayName("임시 토큰은 10분이 지나면 쓸 수 없다")
    void tokenExpires() throws Exception {
        signup("abcdef@gmail.com", "Abcd1234!", "길동하나", "홍길동", "01099998888");
        String body = postJson("/api/auth/find-email", FIND).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.results[0].resetToken");
        clock.advance(Duration.ofMinutes(11));
        postJson("/api/auth/password/code", "{\"findToken\":\"%s\"}".formatted(token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIND_TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("같은 IP는 10분에 5번까지 찾을 수 있다")
    void rateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            postJson("/api/auth/find-email", FIND).andExpect(status().isOk());
        }
        postJson("/api/auth/find-email", FIND).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("탈퇴한 계정은 결과에서 뺀다")
    void excludesWithdrawn() throws Exception {
        signup("abcdef@gmail.com", "Abcd1234!", "길동하나", "홍길동", "01099998888");
        var cookies = login("abcdef@gmail.com", "Abcd1234!");
        postAs("/api/me/withdraw", "{\"password\":\"Abcd1234!\"}", cookies).andExpect(status().isOk());
        String body = postJson("/api/auth/find-email", FIND).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("ab***");
    }
}
