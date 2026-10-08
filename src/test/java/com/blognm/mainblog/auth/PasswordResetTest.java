package com.blognm.mainblog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.blognm.mainblog.support.IntegrationTest;

/** 001 User Story 3 - 비밀번호 찾기·재설정 */
class PasswordResetTest extends IntegrationTest {

    private static final String EMAIL = "reset@example.com";
    private static final String OLD = "Abcd1234!";
    private static final String NEW = "Newpw123!";

    @BeforeEach
    void setUp() throws Exception {
        signup(EMAIL, OLD, "재설정회원");
        mails.sent().clear();
    }

    private String requestCode(String email) throws Exception {
        postJson("/api/auth/password/code", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isOk());
        return waitForCode(email);
    }

    private org.springframework.test.web.servlet.ResultActions reset(String code, String password) throws Exception {
        return postJson("/api/auth/password/reset", """
                {"email":"%s","code":"%s","newPassword":"%s"}""".formatted(EMAIL, code, password));
    }

    @Test
    @DisplayName("가입 여부와 관계없이 같은 문구가 나오고, 가입 안 된 이메일에는 메일이 가지 않는다")
    void sameMessage() throws Exception {
        String registered = postJson("/api/auth/password/code", "{\"email\":\"%s\"}".formatted(EMAIL))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknown = postJson("/api/auth/password/code", "{\"email\":\"nobody@example.com\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(registered).contains("입력한 이메일로 안내를 보냈습니다");
        assertThat(unknown).contains("입력한 이메일로 안내를 보냈습니다");
        waitForCode(EMAIL);
        Thread.sleep(200);
        assertThat(mails.sent()).noneMatch(m -> m.to().equals("nobody@example.com"));
    }

    @Test
    @DisplayName("인증번호로 비밀번호를 바꾸면 새 비밀번호로 로그인되고 다른 기기 로그인·남은 Access Token이 풀린다")
    void resetSuccess() throws Exception {
        Cookie[] otherDevice = login(EMAIL, OLD);
        clock.advance(java.time.Duration.ofSeconds(2));
        reset(requestCode(EMAIL), NEW).andExpect(status().isOk());

        login(EMAIL, NEW);
        postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, OLD))
                .andExpect(status().isUnauthorized());
        // 다른 기기: Refresh Token은 폐기되고, 아직 5분이 안 된 Access Token도 받지 않는다 (T030, T031)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/refresh")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()
                        .asHeader())
                .cookie(otherDevice[1])).andExpect(status().isUnauthorized());
        getAs("/api/me", otherDevice[0]).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("한 번 쓴 인증번호는 다시 쓸 수 없다")
    void codeIsSingleUse() throws Exception {
        String code = requestCode(EMAIL);
        reset(code, NEW).andExpect(status().isOk());
        reset(code, "Other123!").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    @Test
    @DisplayName("5번 틀리면 맞는 번호도 무효가 된다")
    void fiveFailures() throws Exception {
        String code = requestCode(EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 4; i++) {
            reset(wrong, NEW).andExpect(jsonPath("$.code").value("CODE_MISMATCH"));
        }
        reset(wrong, NEW).andExpect(jsonPath("$.code").value("CODE_INVALID"));
        reset(code, NEW).andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    @Test
    @DisplayName("30분이 지나면 인증번호가 만료된다")
    void expires() throws Exception {
        String code = requestCode(EMAIL);
        clock.advance(java.time.Duration.ofMinutes(31));
        reset(code, NEW).andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    @Test
    @DisplayName("새 비밀번호가 규칙에 맞지 않으면 인증번호를 쓰지 않고 거절한다")
    void weakPassword() throws Exception {
        String code = requestCode(EMAIL);
        reset(code, "short").andExpect(status().isBadRequest());
        reset(code, NEW).andExpect(status().isOk());
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 로그인 잠금도 풀린다")
    void unlocksLogin() throws Exception {
        for (int i = 0; i < 5; i++) {
            postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"Wrong123!\",\"turnstileToken\":\"x\"}"
                    .formatted(EMAIL));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_failures WHERE email = ?", Integer.class, EMAIL))
                .isEqualTo(1);
        reset(requestCode(EMAIL), NEW).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_failures WHERE email = ?", Integer.class, EMAIL))
                .isZero();
    }
}
