package com.blognm.mainblog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import com.blognm.mainblog.config.AppProperties;
import com.blognm.mainblog.support.IntegrationTest;

/**
 * 소셜 로그인 요청 값을 세션이 아니라 암호화한 쿠키에 둔다 (D-111, SCL-01).
 * 로그인 버튼을 누른 서버(app-a)와 회사에서 돌아온 요청을 받은 서버(app-b)가 달라도 같은 값을 읽어야 한다.
 * 다른 테스트의 csrf() 도우미가 공유 컨텍스트의 CSRF 저장소를 세션 방식으로 바꿔 두므로, 세션이 생기지 않는지 보려면 새 컨텍스트에서 돌린다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CookieAuthorizationRequestTest extends IntegrationTest {

    @Autowired
    AppProperties props;

    @Autowired
    CookieOAuth2AuthorizationRequestRepository repository;

    /** 로그인 버튼을 누른 것처럼 /oauth2/authorization/{회사}를 부른다. */
    private MvcResult start(String provider) throws Exception {
        return mvc.perform(get("/oauth2/authorization/" + provider)).andExpect(status().is3xxRedirection())
                .andReturn();
    }

    private static String stateOf(MvcResult r) {
        String raw = UriComponentsBuilder.fromUriString(r.getResponse().getRedirectedUrl()).build().getQueryParams()
                .getFirst("state");
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static Cookie cookieOf(MvcResult r) {
        return r.getResponse().getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE);
    }

    /** 다른 서버: 메모리를 나누지 않는, 같은 비밀 키로 새로 만든 저장소 */
    private CookieOAuth2AuthorizationRequestRepository otherInstance() {
        return new CookieOAuth2AuthorizationRequestRepository(props, clock);
    }

    private static MockHttpServletRequest callback(String provider, String state, Cookie cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/login/oauth2/code/" + provider);
        req.setParameter(OAuth2ParameterNames.STATE, state);
        req.setParameter(OAuth2ParameterNames.CODE, "code-from-provider");
        if (cookie != null) {
            req.setCookies(cookie);
        }
        return req;
    }

    @Test
    @DisplayName("로그인 버튼을 누르면 세션을 만들지 않고, 돌아오는 주소에만 가는 3분짜리 HttpOnly·Secure·Lax 쿠키에 암호화해 담는다")
    void savedInCookieNotSession() throws Exception {
        MvcResult r = start("google");
        assertThat(r.getRequest().getSession(false)).as("세션을 만들지 않는다").isNull();
        assertThat(r.getResponse().getCookie("JSESSIONID")).isNull();
        Cookie cookie = cookieOf(r);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/login/oauth2/code");
        assertThat(cookie.getMaxAge()).isEqualTo((int) Duration.ofMinutes(3).toSeconds());
        String header = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(CookieOAuth2AuthorizationRequestRepository.COOKIE + "=")).findFirst()
                .orElseThrow();
        assertThat(header).contains("SameSite=Lax");
        assertThat(cookie.getValue()).as("state·client_id가 그대로 보이지 않는다")
                .doesNotContain(stateOf(r)).doesNotContain("test-google-client-id");
    }

    @Test
    @DisplayName("구글·네이버·카카오 모두 다른 서버가 쿠키에서 같은 요청 값(계정 고르기 포함)을 되살리고, 쓴 쿠키는 지운다")
    void restoredOnOtherInstance() throws Exception {
        for (String provider : new String[] { "google", "naver", "kakao" }) {
            MvcResult r = start(provider);
            Cookie cookie = cookieOf(r);
            assertThat(cookie.getValue().length()).as(provider + " 쿠키 크기")
                    .isLessThan(CookieOAuth2AuthorizationRequestRepository.MAX_VALUE_LENGTH);
            OAuth2AuthorizationRequest original = repository.decode(cookie.getValue());

            MockHttpServletRequest back = callback(provider, stateOf(r), cookie);
            MockHttpServletResponse response = new MockHttpServletResponse();
            OAuth2AuthorizationRequest restored = otherInstance().removeAuthorizationRequest(back, response);

            assertThat(restored).as(provider).isNotNull();
            assertThat(restored.getState()).isEqualTo(stateOf(r));
            assertThat(restored.getClientId()).isEqualTo("test-" + provider + "-client-id");
            assertThat(restored.getRedirectUri()).isEqualTo(original.getRedirectUri())
                    .endsWith("/login/oauth2/code/" + provider);
            assertThat(restored.getScopes()).isEqualTo(original.getScopes());
            assertThat(restored.getAttributes()).as("registration_id·PKCE·nonce").isEqualTo(original.getAttributes())
                    .containsEntry(OAuth2ParameterNames.REGISTRATION_ID, provider);
            assertThat(restored.getAdditionalParameters()).isEqualTo(original.getAdditionalParameters());
            // 계정 고르기 (D-108): 카카오·구글만 prompt=select_account
            if (provider.equals("naver")) {
                assertThat(restored.getAdditionalParameters()).doesNotContainKey("prompt");
            } else {
                assertThat(restored.getAdditionalParameters()).containsEntry("prompt", "select_account");
            }
            assertThat(restored.getAuthorizationRequestUri()).as("나머지 값으로 다시 만든 주소")
                    .isEqualTo(r.getResponse().getRedirectedUrl());
            assertThat(back.getSession(false)).isNull();
            Cookie cleared = response.getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE);
            assertThat(cleared.getMaxAge()).as("한 번 쓰면 지운다").isZero();
            assertThat(cleared.getPath()).isEqualTo("/login/oauth2/code");
        }
    }

    @Test
    @DisplayName("PKCE code_verifier와 nonce가 다른 서버에서도 그대로 돌아온다")
    void pkceRoundTrip() {
        OAuth2AuthorizationRequest.Builder b = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .clientId("test-kakao-client-id")
                .redirectUri("https://creat.java21.net/login/oauth2/code/kakao")
                .state("state-123")
                .attributes(a -> a.put(OAuth2ParameterNames.REGISTRATION_ID, "kakao"));
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(b);
        OAuth2AuthorizationRequest request = b.build();
        String verifier = request.getAttribute(PkceParameterNames.CODE_VERIFIER);
        assertThat(verifier).isNotBlank();

        MockHttpServletResponse saved = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(request, new MockHttpServletRequest(), saved);
        Cookie cookie = saved.getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE);

        OAuth2AuthorizationRequest restored = otherInstance()
                .loadAuthorizationRequest(callback("kakao", "state-123", cookie));
        assertThat(restored).isNotNull();
        assertThat((String) restored.getAttribute(PkceParameterNames.CODE_VERIFIER)).isEqualTo(verifier);
        assertThat(restored.getAdditionalParameters())
                .containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256")
                .containsKey(PkceParameterNames.CODE_CHALLENGE);
        assertThat(restored.getAuthorizationRequestUri()).isEqualTo(request.getAuthorizationRequestUri());
    }

    @Test
    @DisplayName("state가 다르거나, 쿠키를 고쳤거나, 다른 키로 만든 쿠키는 읽지 않는다")
    void rejectsWrongCookie() throws Exception {
        MvcResult r = start("google");
        Cookie cookie = cookieOf(r);
        String state = stateOf(r);

        assertThat(otherInstance().loadAuthorizationRequest(callback("google", "other-state", cookie))).isNull();

        String v = cookie.getValue();
        for (int at : new int[] { 3, v.length() / 2, v.length() - 5 }) {
            char c = v.charAt(at);
            String changed = v.substring(0, at) + (c == 'A' ? 'B' : 'A') + v.substring(at + 1);
            assertThat(otherInstance()
                    .loadAuthorizationRequest(callback("google", state, new Cookie(cookie.getName(), changed))))
                    .as("고친 위치 %d", at).isNull();
        }
        assertThat(otherInstance().loadAuthorizationRequest(
                callback("google", state, new Cookie(cookie.getName(), v.substring(0, v.length() - 10))))).isNull();
        assertThat(otherInstance().loadAuthorizationRequest(
                callback("google", state, new Cookie(cookie.getName(), "x".repeat(5000))))).isNull();

        AppProperties.Auth a = props.auth();
        AppProperties otherKey = new AppProperties(props.timeZone(), props.baseUrl(), props.mail(),
                new AppProperties.Auth("another-jwt-secret-0123456789-abcdefghijk", a.accessTokenTtl(),
                        a.rememberMeTtl(), a.idleTimeout(), a.sessionMaxTtl(), a.cookieSecure(), a.codeHashSecret()),
                props.signup(), props.passwordReset(), props.emailFind(), props.login(), props.rateLimit(),
                props.recaptcha());
        assertThat(new CookieOAuth2AuthorizationRequestRepository(otherKey, clock)
                .loadAuthorizationRequest(callback("google", state, cookie))).isNull();
        assertThat(otherInstance().loadAuthorizationRequest(callback("google", state, cookie))).isNotNull();
    }

    @Test
    @DisplayName("3분이 지난 쿠키는 브라우저가 다시 보내도 서버가 받지 않는다")
    void expiresOnServerToo() throws Exception {
        MvcResult r = start("naver");
        Cookie cookie = cookieOf(r);
        clock.advance(Duration.ofMinutes(2));
        assertThat(otherInstance().loadAuthorizationRequest(callback("naver", stateOf(r), cookie))).isNotNull();
        clock.advance(Duration.ofMinutes(2));
        assertThat(otherInstance().loadAuthorizationRequest(callback("naver", stateOf(r), cookie))).isNull();
        clock.reset();
    }

    @Test
    @DisplayName("너무 큰 요청 값은 쿠키로 쓰지 않는다")
    void tooLargeIsNotWritten() {
        OAuth2AuthorizationRequest huge = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("c").redirectUri("https://creat.java21.net/login/oauth2/code/google").state("s")
                .additionalParameters(Map.of("big", "y".repeat(4000)))
                .build();
        MockHttpServletResponse res = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(huge, new MockHttpServletRequest(), res);
        assertThat(res.getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE)).isNull();
    }

    @Test
    @DisplayName("쿠키 없이 돌아오면 로그인 실패 화면으로 보내고, 세션을 만들지 않는다")
    void callbackWithoutCookieFails() throws Exception {
        MvcResult r = start("google");
        MvcResult back = mvc.perform(get("/login/oauth2/code/google").param("code", "x").param("state", stateOf(r)))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login.html?social=failed&provider=google");
        assertThat(back.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("쿠키를 고쳐 돌아와도 실패 화면으로 보내고, 그 쿠키를 지운다")
    void callbackWithTamperedCookieFailsAndClears() throws Exception {
        MvcResult r = start("kakao");
        Cookie cookie = cookieOf(r);
        String v = cookie.getValue();
        Cookie bad = new Cookie(cookie.getName(), v.substring(0, 20) + (v.charAt(20) == 'A' ? 'B' : 'A') + v.substring(21));
        MvcResult back = mvc.perform(get("/login/oauth2/code/kakao").param("code", "x").param("state", stateOf(r))
                .cookie(bad)).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login.html?social=failed&provider=kakao");
        assertThat(back.getResponse().getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE).getMaxAge())
                .isZero();
    }
}
