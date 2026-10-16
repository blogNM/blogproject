package com.blognm.mainblog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.blognm.mainblog.support.IntegrationTest;

/** 네이버 로그인 (D-107). 네이버에 다녀온 결과는 네이버 사용자 정보 모양({resultcode, message, response})으로 직접 만든다. */
class NaverLoginTest extends IntegrationTest {

    @Autowired
    SocialLoginHandler handler;

    /** 네이버에서 돌아왔을 때와 같은 처리를 하고 응답(쿠키·이동 주소)을 돌려준다. email이 null이면 이메일 제공에 동의하지 않은 것. */
    private MockHttpServletResponse naverReturns(String id, String email, String name) throws Exception {
        Map<String, Object> res = new HashMap<>();
        res.put("id", id);
        res.put("nickname", "네이버별명");
        if (email != null) {
            res.put("email", email);
        }
        if (name != null) {
            res.put("name", name);
        }
        Map<String, Object> attributes = Map.of("resultcode", "00", "message", "success", "response", res);
        DefaultOAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes,
                "response");
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(user, user.getAuthorities(), "naver");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, auth);
        assertThat(request.getSession(false)).as("네이버를 다녀오며 쓴 세션은 지운다").isNull();
        return response;
    }

    private Cookie expectLoggedInAs(MockHttpServletResponse response, String nickname) throws Exception {
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        Cookie access = response.getCookie(AuthCookies.ACCESS);
        assertThat(access).isNotNull();
        mvc.perform(get("/api/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(nickname));
        return access;
    }

    private MvcResult finishSignup(Cookie pending, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/auth/social/signup").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(json).cookie(pending)).andReturn();
    }

    private int linkedCount(String naverId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM social_accounts WHERE provider = 'naver' AND provider_user_id = ?",
                Integer.class, naverId);
    }

    @Test
    @DisplayName("네이버 앱 값이 있으면 버튼을 보이고, 버튼 주소는 네이버 로그인 화면으로 보낸다")
    void naverButton() throws Exception {
        mvc.perform(get("/api/auth/social/config"))
                .andExpect(jsonPath("$.google").value(true))
                .andExpect(jsonPath("$.naver").value(true));
        MvcResult r = mvc.perform(get("/oauth2/authorization/naver")).andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).startsWith("https://nid.naver.com/oauth2.0/authorize")
                .contains("response_type=code")
                .contains("client_id=test-naver-client-id")
                .contains("state=")
                .contains("redirect_uri=http://localhost/login/oauth2/code/naver")
                .as("네이버는 계정 고르기 화면이 없어 prompt를 붙이지 않는다")
                .doesNotContain("prompt=");
    }

    @Test
    @DisplayName("NAVER_CLIENT_ID가 비어 있으면 네이버 버튼을 숨긴다 (구글만 있을 때)")
    void naverButtonHiddenWithoutClientId() {
        ClientRegistrationRepository googleOnly = new SocialLoginConfig()
                .clientRegistrationRepository("google-id", "google-secret", "", "", "", "");
        assertThat(((InMemoryClientRegistrationRepository) googleOnly).iterator()).toIterable().hasSize(1);
        SocialAuthController controller = new SocialAuthController(null, null,
                new StaticListableBeanFactory(Map.of("clients", googleOnly)).getBeanProvider(ClientRegistrationRepository.class));
        assertThat(controller.config()).containsEntry("google", true).containsEntry("naver", false);

        ClientRegistrationRepository naverOnly = new SocialLoginConfig()
                .clientRegistrationRepository("", "", "naver-id", "naver-secret", "", "");
        controller = new SocialAuthController(null, null,
                new StaticListableBeanFactory(Map.of("clients", naverOnly)).getBeanProvider(ClientRegistrationRepository.class));
        assertThat(controller.config()).containsEntry("google", false).containsEntry("naver", true);
        var naver = naverOnly.findByRegistrationId("naver");
        assertThat(naver.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("response");
        assertThat(naver.getClientAuthenticationMethod().getValue()).isEqualTo("client_secret_post");

        controller = new SocialAuthController(null, null, new StaticListableBeanFactory()
                .getBeanProvider(ClientRegistrationRepository.class));
        assertThat(controller.config()).containsEntry("google", false).containsEntry("naver", false);
    }

    @Test
    @DisplayName("같은 이메일로 가입한 회원은 네이버 계정이 연결되어 바로 로그인되고, 탈퇴하면 연결이 끊긴다")
    void existingMemberLinksAndWithdrawUnlinks() throws Exception {
        signup("naver-user@example.com", "Abcd1234!", "이메일회원");

        expectLoggedInAs(naverReturns("n-1", "Naver-User@example.com", "김네이버"), "이메일회원");
        assertThat(linkedCount("n-1")).isEqualTo(1);

        // 네이버에서 이메일을 바꿔도 같은 회원으로 로그인된다
        Cookie access = expectLoggedInAs(naverReturns("n-1", "changed@naver.com", "김네이버"), "이메일회원");

        // 가입 화면은 네이버로 로그인하라고 알려 준다. 예전 화면용 google 값은 false
        mvc.perform(get("/api/auth/signup/email-available").param("email", "naver-user@example.com"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.google").value(false))
                .andExpect(jsonPath("$.provider").value("naver"));
        postJson("/api/auth/signup/code", "{\"email\":\"naver-user@example.com\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("네이버 계정으로 가입된 이메일입니다. 로그인 화면에서 네이버로 로그인해 주세요."));

        mvc.perform(MockMvcRequestBuilders.post("/api/me/withdraw").with(csrf().asHeader()).cookie(access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Abcd1234!\"}"))
                .andExpect(status().isOk());
        assertThat(linkedCount("n-1")).isZero();

        // 탈퇴 뒤 같은 네이버 계정으로 오면 처음 온 사람처럼 가입 화면으로 간다
        assertThat(naverReturns("n-1", "naver-user@example.com", null).getRedirectedUrl())
                .isEqualTo("/social-signup.html");
    }

    @Test
    @DisplayName("처음 온 사람은 네이버 이메일로 가입 화면에 가고, 닉네임과 약관 동의로 가입한다")
    void newMemberSignsUp() throws Exception {
        MockHttpServletResponse back = naverReturns("n-2", "new@naver.com", "홍길동");
        assertThat(back.getRedirectedUrl()).isEqualTo("/social-signup.html");
        Cookie pending = back.getCookie(AuthCookies.SOCIAL_SIGNUP);
        assertThat(pending.isHttpOnly()).isTrue();
        assertThat(back.getCookie(AuthCookies.ACCESS)).isNull();

        mvc.perform(get("/api/auth/social/pending").cookie(pending))
                .andExpect(jsonPath("$.email").value("new@naver.com"))
                .andExpect(jsonPath("$.provider").value("naver"));

        MvcResult ok = finishSignup(pending, """
                {"nickname":"네이버회원","password":"Abcd1234!","agreeTerms":true,"agreePrivacy":true,"age14":true}""");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/me").cookie(ok.getResponse().getCookie(AuthCookies.ACCESS)))
                .andExpect(jsonPath("$.nickname").value("네이버회원"));
        Map<String, Object> row = jdbc.queryForMap("SELECT email, name FROM members WHERE nickname = '네이버회원'");
        assertThat(row.get("email")).isEqualTo("new@naver.com");
        assertThat(row.get("name")).isEqualTo("홍길동");
        assertThat(linkedCount("n-2")).isEqualTo(1);

        // 다음에는 가입 화면 없이 바로 로그인
        expectLoggedInAs(naverReturns("n-2", "new@naver.com", "홍길동"), "네이버회원");
    }

    @Test
    @DisplayName("네이버에서 이메일 제공에 동의하지 않으면 로그인 화면에 이유를 알려 주고 가입시키지 않는다")
    void missingEmailRejected() throws Exception {
        MockHttpServletResponse back = naverReturns("n-3", null, "이메일없음");
        assertThat(back.getRedirectedUrl()).isEqualTo("/login.html?social=no-email&provider=naver");
        assertThat(back.getCookie(AuthCookies.ACCESS)).isNull();
        assertThat(back.getCookie(AuthCookies.SOCIAL_SIGNUP)).isNull();
        assertThat(linkedCount("n-3")).isZero();
    }

    @Test
    @DisplayName("네이버 로그인을 중간에 실패하면 네이버 실패로 알려 준다")
    void failureNamesNaver() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/naver");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response,
                new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token"));
        assertThat(response.getRedirectedUrl()).isEqualTo("/login.html?social=failed&provider=naver");
    }
}
