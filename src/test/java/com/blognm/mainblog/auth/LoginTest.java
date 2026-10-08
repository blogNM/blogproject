package com.blognm.mainblog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import com.blognm.mainblog.support.IntegrationTest;

/** 001 User Story 2 - 로그인과 로그아웃 */
class LoginTest extends IntegrationTest {

    private static final String EMAIL = "login@example.com";
    private static final String PASSWORD = "Abcd1234!";

    @MockitoBean
    TurnstileVerifier turnstile;

    @BeforeEach
    void setUp() throws Exception {
        signup(EMAIL, PASSWORD, "로그인회원");
        when(turnstile.siteKey()).thenReturn("test-site-key");
        when(turnstile.verify(eq("good-token"), any())).thenReturn(true);
    }

    private MvcResult login(String password, boolean rememberMe, String turnstileToken) throws Exception {
        String token = turnstileToken == null ? "null" : "\"" + turnstileToken + "\"";
        return postJson("/api/auth/login", """
                {"email":"%s","password":"%s","rememberMe":%s,"turnstileToken":%s}"""
                .formatted(EMAIL, password, rememberMe, token)).andReturn();
    }

    private static Cookie cookie(MvcResult r, String name) {
        return r.getResponse().getCookie(name);
    }

    private static String setCookieHeader(MvcResult r, String name) {
        List<String> headers = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return headers.stream().filter(h -> h.startsWith(name + "=")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("로그인하면 HttpOnly·Secure 토큰 쿠키가 생기고 /api/me로 내 닉네임이 보인다")
    void loginSuccess() throws Exception {
        MvcResult r = login(PASSWORD, false, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        String access = setCookieHeader(r, AuthCookies.ACCESS);
        assertThat(access).contains("HttpOnly").contains("Secure").doesNotContain("Max-Age");
        assertThat(setCookieHeader(r, AuthCookies.REFRESH)).contains("Path=/api/auth").doesNotContain("Max-Age");

        mvc.perform(get("/api/me").cookie(cookie(r, AuthCookies.ACCESS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("로그인회원"));
    }

    @Test
    @DisplayName("로그인 유지를 체크하면 쿠키가 14일 유지된다")
    void rememberMe() throws Exception {
        MvcResult r = login(PASSWORD, true, null);
        assertThat(setCookieHeader(r, AuthCookies.REFRESH)).contains("Max-Age=" + Duration.ofDays(14).toSeconds());

        clock.advance(Duration.ofDays(13));
        refresh(cookie(r, AuthCookies.REFRESH), false).andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인 유지 없이 30분 동안 직접 한 행동이 없으면 로그아웃된다 (D-62)")
    void idleTimeout() throws Exception {
        MvcResult r = login(PASSWORD, false, null);
        Cookie refresh = cookie(r, AuthCookies.REFRESH);

        // 자동 요청(active=false)만 오가면 무활동 시간은 다시 시작되지 않는다
        clock.advance(Duration.ofMinutes(20));
        refresh(refresh, false).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(11));
        refresh(refresh, false).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("직접 한 행동(active=true)이 있으면 30분이 다시 시작된다")
    void activityExtends() throws Exception {
        MvcResult r = login(PASSWORD, false, null);
        Cookie refresh = cookie(r, AuthCookies.REFRESH);

        clock.advance(Duration.ofMinutes(20));
        refresh(refresh, true).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(20));
        refresh(refresh, false).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Access Token이 만료되면 /api/me는 401이다")
    void accessTokenExpires() throws Exception {
        MvcResult r = login(PASSWORD, false, null);
        clock.advance(Duration.ofMinutes(6));
        mvc.perform(get("/api/me").cookie(cookie(r, AuthCookies.ACCESS))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃하면 서버의 Refresh Token이 폐기되어 다시 쓸 수 없다 (SC-003)")
    void logoutRevokesRefreshToken() throws Exception {
        MvcResult r = login(PASSWORD, false, null);
        Cookie refresh = cookie(r, AuthCookies.REFRESH);

        MvcResult out = mvc.perform(post("/api/auth/logout").with(csrf().asHeader()).cookie(refresh)).andReturn();
        assertThat(setCookieHeader(out, AuthCookies.ACCESS)).contains("Max-Age=0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isZero();

        refresh(refresh, true).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("3번 실패하면 사람 확인을 요구하고, 통과해야 로그인된다 (SEC-13)")
    void captchaAfterThreeFailures() throws Exception {
        for (int i = 0; i < 2; i++) {
            assertThat(login("Wrong123!", false, null).getResponse().getContentAsString())
                    .contains("\"captchaRequired\":false");
        }
        assertThat(login("Wrong123!", false, null).getResponse().getContentAsString())
                .contains("\"captchaRequired\":true");

        MvcResult noCaptcha = login(PASSWORD, false, null);
        assertThat(noCaptcha.getResponse().getContentAsString()).contains("CAPTCHA_REQUIRED");

        MvcResult ok = login(PASSWORD, false, "good-token");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_failures", Integer.class)).isZero();
    }

    @Test
    @DisplayName("5번 연속 실패하면 맞는 비밀번호로도 5분 동안 잠긴다 (SEC-03)")
    void lockAfterFiveFailures() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("Wrong123!", false, "good-token");
        }
        MvcResult fifth = login("Wrong123!", false, "good-token");
        assertThat(fifth.getResponse().getStatus()).isEqualTo(423);

        assertThat(login(PASSWORD, false, "good-token").getResponse().getStatus()).isEqualTo(423);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(login(PASSWORD, false, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("가입 안 된 이메일도 같은 문구로 실패한다")
    void unknownEmailSameMessage() throws Exception {
        String unknown = postJson("/api/auth/login",
                "{\"email\":\"nobody@example.com\",\"password\":\"Abcd1234!\",\"rememberMe\":false}")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String wrongPw = login("Wrong123!", false, null).getResponse().getContentAsString();
        assertThat(unknown).isEqualTo(wrongPw);
    }

    @Test
    @DisplayName("CSRF 헤더가 없는 로그인 요청은 403이다 (SEC-10)")
    void csrfRequired() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("같은 IP에서 1분에 20번 넘게 로그인하면 거절된다")
    void loginRateLimit() throws Exception {
        for (int i = 0; i < 20; i++) {
            postJson("/api/auth/login", "{\"email\":\"r%d@example.com\",\"password\":\"x\"}".formatted(i));
        }
        postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions refresh(Cookie refreshCookie, boolean active)
            throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(csrf().asHeader()).cookie(refreshCookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":%s}".formatted(active)));
    }
}
