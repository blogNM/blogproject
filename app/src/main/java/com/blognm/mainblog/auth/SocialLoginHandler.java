package com.blognm.mainblog.auth;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 구글(D-103)·네이버(D-107)·카카오(D-108)에서 돌아온 뒤 처리. 스프링 시큐리티 세션 로그인은 쓰지 않고, 이메일 로그인과 같은 JWT 쿠키를 내려준다.
 * 바깥 로그인 화면에 다녀오는 동안 기억할 값은 세션이 아니라 암호화한 쿠키에 둔다 (CookieOAuth2AuthorizationRequestRepository, D-111).
 * 혹시 세션이 생겼으면 여기서 지운다 (SCL-01).
 * 실패하면 로그인 화면에 social=(failed|unverified|no-email)과 provider=(google|naver|kakao)를 붙여 돌려보낸다.
 */
@Component
public class SocialLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final String CALLBACK_PREFIX = "/login/oauth2/code/";

    private final SocialLoginService socialLogin;
    private final AuthCookies cookies;

    public SocialLoginHandler(SocialLoginService socialLogin, AuthCookies cookies) {
        this.socialLogin = socialLogin;
        this.cookies = cookies;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        endSession(request);
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            response.sendRedirect(loginPage("failed", null));
            return;
        }
        String provider = token.getAuthorizedClientRegistrationId();
        Profile profile = profileOf(provider, token.getPrincipal());
        if (profile == null) {
            response.sendRedirect(loginPage("failed", provider));
            return;
        }
        if (profile.email() == null || profile.email().isBlank()) {
            // 네이버에서 이메일 제공에 동의하지 않은 경우 (D-107), 카카오에서 인증된 이메일을 받지 못한 경우 (D-108)
            response.sendRedirect(loginPage("no-email", provider));
            return;
        }
        SocialLoginService.Outcome outcome = socialLogin.login(provider, profile.id(), profile.email(),
                profile.emailVerified(), profile.name());
        if (outcome.tokens() != null) {
            cookies.write(response, outcome.tokens().accessToken(), outcome.tokens().refreshToken(), false);
            response.sendRedirect("/");
        } else if (outcome.signupToken() != null) {
            cookies.writeSocialSignup(response, outcome.signupToken(), SocialLoginService.SIGNUP_TTL);
            response.sendRedirect("/social-signup.html");
        } else {
            response.sendRedirect(loginPage("unverified", provider));
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        endSession(request);
        String uri = request.getRequestURI();
        String provider = uri != null && uri.startsWith(CALLBACK_PREFIX) ? uri.substring(CALLBACK_PREFIX.length()) : null;
        response.sendRedirect(loginPage("failed", provider));
    }

    /** 회사마다 다른 사용자 정보 모양을 하나로 맞춘다. 모르는 모양이면 null. */
    static Profile profileOf(String provider, OAuth2User principal) {
        if (SocialAccount.GOOGLE.equals(provider) && principal instanceof OidcUser user) {
            return new Profile(user.getSubject(), user.getEmail(), Boolean.TRUE.equals(user.getEmailVerified()),
                    user.getFullName());
        }
        if (SocialAccount.NAVER.equals(provider) && principal != null
                && principal.getAttributes().get("response") instanceof Map<?, ?> res) {
            Object id = res.get("id");
            if (id == null || id.toString().isBlank()) {
                return null;
            }
            // 네이버는 이메일 확인 여부를 따로 주지 않는다. 네이버가 계정에 둔 연락 이메일이라 확인된 것으로 본다 (D-107).
            return new Profile(id.toString(), text(res.get("email")), true, text(res.get("name")));
        }
        if (SocialAccount.KAKAO.equals(provider) && principal != null) {
            Object id = principal.getAttributes().get("id");
            if (id == null || id.toString().isBlank()) {
                return null;
            }
            // 카카오 id는 숫자라 글자로 바꿔 저장한다. 이메일은 유효하고(is_email_valid) 인증된(is_email_verified)
            // 것만 쓴다. 아니면 이메일이 없는 것으로 보고 로그인 화면에 안내한다 (D-108).
            String email = null;
            if (principal.getAttributes().get("kakao_account") instanceof Map<?, ?> account
                    && Boolean.TRUE.equals(account.get("is_email_valid"))
                    && Boolean.TRUE.equals(account.get("is_email_verified"))) {
                email = text(account.get("email"));
            }
            // 이름은 받지 않는다 (scope는 account_email만, 닉네임은 가입 화면에서 직접 정함)
            return new Profile(id.toString(), email, true, null);
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String loginPage(String reason, String provider) {
        boolean known = SocialAccount.GOOGLE.equals(provider) || SocialAccount.NAVER.equals(provider)
                || SocialAccount.KAKAO.equals(provider);
        return "/login.html?social=" + reason + (known ? "&provider=" + provider : "");
    }

    private static void endSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    record Profile(String id, String email, boolean emailVerified, String name) {
    }
}
