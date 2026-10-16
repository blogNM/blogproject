package com.blognm.mainblog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.blognm.mainblog.support.IntegrationTest;

/** 구글 로그인 (D-103). 구글에 다녀온 결과는 OidcUser를 직접 만들어 넣는다. */
class SocialLoginTest extends IntegrationTest {

    @Autowired
    SocialLoginHandler handler;

    /** 구글에서 돌아왔을 때와 같은 처리를 하고 응답(쿠키·이동 주소)을 돌려준다. */
    private MockHttpServletResponse googleReturns(String sub, String email, boolean verified, String name)
            throws Exception {
        Map<String, Object> claims = Map.of("sub", sub, "email", email, "email_verified", verified, "name", name,
                "iss", "https://accounts.google.com");
        OidcIdToken idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60), claims);
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, auth);
        assertThat(request.getSession(false)).as("구글을 다녀오며 쓴 세션은 지운다").isNull();
        return response;
    }

    private void expectLoggedInAs(MockHttpServletResponse response, String nickname) throws Exception {
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        Cookie access = response.getCookie(AuthCookies.ACCESS);
        assertThat(access).isNotNull();
        mvc.perform(get("/api/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(nickname));
    }

    private MvcResult finishSignup(Cookie pending, String json) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/auth/social/signup").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (pending != null) {
            req.cookie(pending);
        }
        return mvc.perform(req).andReturn();
    }

    @Test
    @DisplayName("구글 앱 값이 있으면 로그인 화면에 버튼을 보이고, 버튼 주소는 구글 계정 고르기 화면으로 보낸다")
    void googleButton() throws Exception {
        mvc.perform(get("/api/auth/social/config")).andExpect(jsonPath("$.google").value(true));
        MvcResult r = mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).startsWith("https://accounts.google.com/o/oauth2/v2/auth")
                .contains("client_id=test-google-client-id")
                .as("로그아웃한 뒤 다른 구글 계정을 고를 수 있게 매번 계정 고르기 화면을 띄운다")
                .contains("prompt=select_account");
    }

    @Test
    @DisplayName("같은 이메일로 가입한 회원은 구글 계정이 연결되어 바로 로그인되고, 다음부터는 구글 번호로 찾는다")
    void existingMemberLinks() throws Exception {
        signup("google-user@example.com", "Abcd1234!", "이메일회원");

        expectLoggedInAs(googleReturns("g-1", "Google-User@example.com", true, "김구글"), "이메일회원");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts WHERE provider_user_id = 'g-1'",
                Integer.class)).isEqualTo(1);

        // 구글에서 이메일을 바꿔도 같은 회원으로 로그인된다
        expectLoggedInAs(googleReturns("g-1", "changed@gmail.com", true, "김구글"), "이메일회원");
    }

    private MvcResult link(Cookie pending, String json) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/auth/social/link").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (pending != null) {
            req.cookie(pending);
        }
        return mvc.perform(req).andReturn();
    }

    @Test
    @DisplayName("처음 온 사람은 닉네임·비밀번호·약관 동의를 받아 가입하고 바로 로그인된다")
    void newMemberSignsUp() throws Exception {
        MockHttpServletResponse back = googleReturns("g-2", "new@gmail.com", true, "Kim Google");
        assertThat(back.getRedirectedUrl()).isEqualTo("/social-signup.html");
        Cookie pending = back.getCookie(AuthCookies.SOCIAL_SIGNUP);
        assertThat(pending.isHttpOnly()).isTrue();
        assertThat(back.getCookie(AuthCookies.ACCESS)).isNull();

        mvc.perform(get("/api/auth/social/pending").cookie(pending))
                .andExpect(jsonPath("$.email").value("new@gmail.com"));

        MvcResult noConsent = finishSignup(pending, """
                {"nickname":"구글회원","password":"Abcd1234!","agreeTerms":true,"agreePrivacy":false,"age14":true}""");
        assertThat(noConsent.getResponse().getStatus()).isEqualTo(400);

        // 비밀번호도 함께 정해야 한다 (D-126)
        MvcResult noPassword = finishSignup(pending, """
                {"nickname":"구글회원","agreeTerms":true,"agreePrivacy":true,"age14":true}""");
        assertThat(noPassword.getResponse().getStatus()).isEqualTo(400);
        assertThat(noPassword.getResponse().getContentAsString()).contains("INVALID_PASSWORD");

        MvcResult ok = finishSignup(pending, """
                {"nickname":"구글회원","password":"Abcd1234!","agreeTerms":true,"agreePrivacy":true,"age14":true}""");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/me").cookie(ok.getResponse().getCookie(AuthCookies.ACCESS)))
                .andExpect(jsonPath("$.nickname").value("구글회원"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT email, name, phone, password_hash FROM members WHERE nickname = '구글회원'");
        assertThat(row.get("email")).isEqualTo("new@gmail.com");
        assertThat(row.get("name")).isEqualTo("Kim Google");
        assertThat(row.get("phone")).isNull();
        assertThat((String) row.get("password_hash")).startsWith("$2");

        // 다음에는 가입 화면 없이 바로 로그인
        expectLoggedInAs(googleReturns("g-2", "new@gmail.com", true, "Kim Google"), "구글회원");

        // 가입할 때 정한 비밀번호로 이메일 로그인도 된다. 그래서 비밀번호 찾기도 그대로 쓸 수 있다 (D-126)
        postJson("/api/auth/login", "{\"email\":\"new@gmail.com\",\"password\":\"Abcd1234!\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("구글회원"));

        // 같은 이메일로 일반 가입을 하려 하면 구글로 가입된 이메일이라고 알려 준다
        mvc.perform(get("/api/auth/signup/email-available").param("email", "new@gmail.com"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.google").value(true))
                .andExpect(jsonPath("$.provider").value("google"));
        postJson("/api/auth/signup/code", "{\"email\":\"new@gmail.com\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.message").value(startsWith("구글 계정으로 가입된 이메일입니다.")));
    }

    @Test
    @DisplayName("이메일이 다른 기존 계정도 그 계정의 이메일·비밀번호를 확인하면 구글 계정을 연결하고 로그인한다")
    void linksExistingAccountWithPassword() throws Exception {
        signup("mine@example.com", "Abcd1234!", "기존회원");

        MockHttpServletResponse back = googleReturns("g-5", "other@gmail.com", true, "다른주소");
        assertThat(back.getRedirectedUrl()).isEqualTo("/social-signup.html");
        Cookie pending = back.getCookie(AuthCookies.SOCIAL_SIGNUP);

        MvcResult wrong = link(pending, """
                {"email":"mine@example.com","password":"Wrong1234!"}""");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getContentAsString()).contains("LOGIN_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts", Integer.class)).isZero();

        MvcResult ok = link(pending, """
                {"email":"mine@example.com","password":"Abcd1234!"}""");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getCookie(AuthCookies.SOCIAL_SIGNUP).getMaxAge()).isZero();
        mvc.perform(get("/api/me").cookie(ok.getResponse().getCookie(AuthCookies.ACCESS)))
                .andExpect(jsonPath("$.nickname").value("기존회원"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isEqualTo(1);

        // 다음부터는 고르는 화면 없이 그 회원으로 로그인
        expectLoggedInAs(googleReturns("g-5", "other@gmail.com", true, "다른주소"), "기존회원");
    }

    @Test
    @DisplayName("이미 다른 구글 계정이 연결된 회원에는 덮어써 연결하지 않는다")
    void linkKeepsExistingProvider() throws Exception {
        signup("first@gmail.com", "Abcd1234!", "먼저연결");
        expectLoggedInAs(googleReturns("g-6", "first@gmail.com", true, "먼저"), "먼저연결");

        Cookie pending = googleReturns("g-7", "second@gmail.com", true, "두번째").getCookie(AuthCookies.SOCIAL_SIGNUP);
        MvcResult taken = link(pending, """
                {"email":"first@gmail.com","password":"Abcd1234!"}""");
        assertThat(taken.getResponse().getStatus()).isEqualTo(409);
        assertThat(taken.getResponse().getContentAsString()).contains("PROVIDER_TAKEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_accounts WHERE provider_user_id = 'g-7'",
                Integer.class)).isZero();

        // 구글을 거치지 않으면 연결할 수 없다
        MvcResult none = link(null, """
                {"email":"first@gmail.com","password":"Abcd1234!"}""");
        assertThat(none.getResponse().getStatus()).isEqualTo(400);
        assertThat(none.getResponse().getContentAsString()).contains("SOCIAL_SIGNUP_EXPIRED");
    }

    @Test
    @DisplayName("구글이 이메일을 확인하지 않은 계정은 받지 않는다")
    void unverifiedEmailRejected() throws Exception {
        MockHttpServletResponse back = googleReturns("g-3", "unverified@gmail.com", false, "확인안됨");
        assertThat(back.getRedirectedUrl()).isEqualTo("/login.html?social=unverified&provider=google");
        assertThat(back.getCookie(AuthCookies.ACCESS)).isNull();
        assertThat(back.getCookie(AuthCookies.SOCIAL_SIGNUP)).isNull();
    }

    @Test
    @DisplayName("구글을 거치지 않거나 가짜 쿠키로는 가입할 수 없다")
    void signupNeedsGoogle() throws Exception {
        String body = """
                {"nickname":"가짜회원","password":"Abcd1234!","agreeTerms":true,"agreePrivacy":true,"age14":true}""";
        MvcResult none = finishSignup(null, body);
        assertThat(none.getResponse().getStatus()).isEqualTo(400);
        assertThat(none.getResponse().getContentAsString()).contains("SOCIAL_SIGNUP_EXPIRED");

        MvcResult forged = finishSignup(new Cookie(AuthCookies.SOCIAL_SIGNUP, "forged.token.value"), body);
        assertThat(forged.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isZero();
    }
}
