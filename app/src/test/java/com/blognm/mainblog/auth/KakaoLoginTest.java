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

/**
 * 카카오 로그인 (D-108). 카카오에 다녀온 결과는 카카오 사용자 정보 모양
 * ({id: 숫자, kakao_account:{email, is_email_valid, is_email_verified, profile}})으로 직접 만든다.
 */
class KakaoLoginTest extends IntegrationTest {

    @Autowired
    SocialLoginHandler handler;

    /** 카카오에서 돌아왔을 때와 같은 처리를 하고 응답(쿠키·이동 주소)을 돌려준다. email이 null이면 이메일 제공에 동의하지 않은 것. */
    private MockHttpServletResponse kakaoReturns(long id, String email, boolean valid, boolean verified)
            throws Exception {
        Map<String, Object> account = new HashMap<>();
        account.put("profile", Map.of("nickname", "카카오별명"));
        if (email != null) {
            account.put("email", email);
            account.put("is_email_valid", valid);
            account.put("is_email_verified", verified);
        }
        Map<String, Object> attributes = Map.of("id", id, "connected_at", "2026-10-13T00:00:00Z",
                "kakao_account", account);
        DefaultOAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes,
                "id");
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(user, user.getAuthorities(), "kakao");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, auth);
        assertThat(request.getSession(false)).as("카카오를 다녀오며 쓴 세션은 지운다").isNull();
        return response;
    }

    private MockHttpServletResponse kakaoReturns(long id, String email) throws Exception {
        return kakaoReturns(id, email, true, true);
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

    private int linkedCount(String kakaoId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM social_accounts WHERE provider = 'kakao' AND provider_user_id = ?",
                Integer.class, kakaoId);
    }

    @Test
    @DisplayName("카카오 앱 값이 있으면 버튼을 보이고, 버튼 주소는 이메일 동의만 요청하며 카카오 계정 고르기 화면으로 보낸다")
    void kakaoButton() throws Exception {
        mvc.perform(get("/api/auth/social/config"))
                .andExpect(jsonPath("$.google").value(true))
                .andExpect(jsonPath("$.naver").value(true))
                .andExpect(jsonPath("$.kakao").value(true));
        MvcResult r = mvc.perform(get("/oauth2/authorization/kakao")).andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).startsWith("https://kauth.kakao.com/oauth/authorize")
                .contains("response_type=code")
                .contains("client_id=test-kakao-client-id")
                .contains("scope=account_email&")
                .contains("state=")
                .contains("redirect_uri=http://localhost/login/oauth2/code/kakao")
                .as("로그아웃한 뒤 다른 카카오 계정을 고를 수 있게 매번 계정 고르기 화면을 띄운다")
                .contains("prompt=select_account");
    }

    @Test
    @DisplayName("KAKAO_CLIENT_ID가 비어 있으면 카카오 버튼을 숨긴다")
    void kakaoButtonHiddenWithoutClientId() {
        ClientRegistrationRepository withoutKakao = new SocialLoginConfig()
                .clientRegistrationRepository("google-id", "google-secret", "naver-id", "naver-secret", "", "");
        assertThat(((InMemoryClientRegistrationRepository) withoutKakao).iterator()).toIterable().hasSize(2);
        SocialAuthController controller = new SocialAuthController(null, null,
                new StaticListableBeanFactory(Map.of("clients", withoutKakao)).getBeanProvider(ClientRegistrationRepository.class));
        assertThat(controller.config()).containsEntry("google", true).containsEntry("naver", true)
                .containsEntry("kakao", false);

        ClientRegistrationRepository kakaoOnly = new SocialLoginConfig()
                .clientRegistrationRepository("", "", "", "", "kakao-id", "kakao-secret");
        controller = new SocialAuthController(null, null,
                new StaticListableBeanFactory(Map.of("clients", kakaoOnly)).getBeanProvider(ClientRegistrationRepository.class));
        assertThat(controller.config()).containsEntry("google", false).containsEntry("naver", false)
                .containsEntry("kakao", true);
        var kakao = kakaoOnly.findByRegistrationId("kakao");
        assertThat(kakao.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("id");
        assertThat(kakao.getProviderDetails().getUserInfoEndpoint().getUri()).isEqualTo("https://kapi.kakao.com/v2/user/me");
        assertThat(kakao.getProviderDetails().getTokenUri()).isEqualTo("https://kauth.kakao.com/oauth/token");
        assertThat(kakao.getClientAuthenticationMethod().getValue()).isEqualTo("client_secret_post");
        assertThat(kakao.getScopes()).containsExactly("account_email");

        controller = new SocialAuthController(null, null, new StaticListableBeanFactory()
                .getBeanProvider(ClientRegistrationRepository.class));
        assertThat(controller.config()).containsEntry("kakao", false);
    }

    @Test
    @DisplayName("같은 이메일로 가입한 회원은 카카오 계정이 연결되어 바로 로그인되고, 탈퇴하면 연결이 끊긴다")
    void existingMemberLinksAndWithdrawUnlinks() throws Exception {
        signup("kakao-user@example.com", "Abcd1234!", "이메일회원");

        expectLoggedInAs(kakaoReturns(4_100_000_001L, "Kakao-User@example.com"), "이메일회원");
        assertThat(linkedCount("4100000001")).as("숫자 id를 글자로 저장한다").isEqualTo(1);

        // 카카오에서 이메일을 바꿔도 같은 회원으로 로그인된다
        Cookie access = expectLoggedInAs(kakaoReturns(4_100_000_001L, "changed@kakao.com"), "이메일회원");

        // 가입 화면은 카카오로 로그인하라고 알려 준다. 예전 화면용 google 값은 false
        mvc.perform(get("/api/auth/signup/email-available").param("email", "kakao-user@example.com"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.google").value(false))
                .andExpect(jsonPath("$.provider").value("kakao"));
        postJson("/api/auth/signup/code", "{\"email\":\"kakao-user@example.com\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("카카오 계정으로 가입된 이메일입니다. 로그인 화면에서 카카오로 로그인해 주세요."));

        mvc.perform(MockMvcRequestBuilders.post("/api/me/withdraw").with(csrf().asHeader()).cookie(access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Abcd1234!\"}"))
                .andExpect(status().isOk());
        assertThat(linkedCount("4100000001")).isZero();

        // 탈퇴 뒤 같은 카카오 계정으로 오면 처음 온 사람처럼 가입 화면으로 간다
        assertThat(kakaoReturns(4_100_000_001L, "kakao-user@example.com").getRedirectedUrl())
                .isEqualTo("/social-signup.html");
    }

    @Test
    @DisplayName("처음 온 사람은 카카오 이메일로 가입 화면에 가고, 닉네임과 약관 동의로 가입한다")
    void newMemberSignsUp() throws Exception {
        MockHttpServletResponse back = kakaoReturns(4_100_000_002L, "new@kakao.com");
        assertThat(back.getRedirectedUrl()).isEqualTo("/social-signup.html");
        Cookie pending = back.getCookie(AuthCookies.SOCIAL_SIGNUP);
        assertThat(pending.isHttpOnly()).isTrue();
        assertThat(back.getCookie(AuthCookies.ACCESS)).isNull();

        mvc.perform(get("/api/auth/social/pending").cookie(pending))
                .andExpect(jsonPath("$.email").value("new@kakao.com"))
                .andExpect(jsonPath("$.provider").value("kakao"));

        MvcResult ok = finishSignup(pending, """
                {"nickname":"카카오회원","agreeTerms":true,"agreePrivacy":true,"age14":true}""");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/me").cookie(ok.getResponse().getCookie(AuthCookies.ACCESS)))
                .andExpect(jsonPath("$.nickname").value("카카오회원"));
        Map<String, Object> row = jdbc.queryForMap("SELECT email, name FROM members WHERE nickname = '카카오회원'");
        assertThat(row.get("email")).isEqualTo("new@kakao.com");
        assertThat(row.get("name")).as("카카오 별명은 이름으로 쓰지 않는다").isNull();
        assertThat(linkedCount("4100000002")).isEqualTo(1);

        // 다음에는 가입 화면 없이 바로 로그인
        expectLoggedInAs(kakaoReturns(4_100_000_002L, "new@kakao.com"), "카카오회원");
    }

    @Test
    @DisplayName("카카오에서 이메일이 없거나 인증되지 않았으면 로그인 화면에 이유를 알려 주고 가입시키지 않는다")
    void missingOrUnverifiedEmailRejected() throws Exception {
        signup("unverified@kakao.com", "Abcd1234!", "인증안된회원");
        for (MockHttpServletResponse back : List.of(
                kakaoReturns(4_100_000_003L, null, false, false),
                kakaoReturns(4_100_000_003L, "unverified@kakao.com", true, false),
                kakaoReturns(4_100_000_003L, "unverified@kakao.com", false, true))) {
            assertThat(back.getRedirectedUrl()).isEqualTo("/login.html?social=no-email&provider=kakao");
            assertThat(back.getCookie(AuthCookies.ACCESS)).isNull();
            assertThat(back.getCookie(AuthCookies.SOCIAL_SIGNUP)).isNull();
        }
        assertThat(linkedCount("4100000003")).as("인증되지 않은 이메일로 같은 이메일 회원에 연결하지 않는다").isZero();
    }

    @Test
    @DisplayName("카카오 로그인을 중간에 실패하면 카카오 실패로 알려 준다")
    void failureNamesKakao() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response,
                new org.springframework.security.oauth2.core.OAuth2AuthenticationException("KOE205"));
        assertThat(response.getRedirectedUrl()).isEqualTo("/login.html?social=failed&provider=kakao");
    }
}
