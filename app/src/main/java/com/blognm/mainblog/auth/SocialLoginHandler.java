package com.blognm.mainblog.auth;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 구글에서 돌아온 뒤 처리 (D-103). 스프링 시큐리티 세션 로그인은 쓰지 않고, 이메일 로그인과 같은 JWT 쿠키를 내려준다.
 * 구글로 보내고 받는 동안 잠깐 쓴 세션은 여기서 지운다 (SCL-01).
 */
@Component
public class SocialLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

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
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !(token.getPrincipal() instanceof OidcUser user)) {
            response.sendRedirect("/login.html?social=failed");
            return;
        }
        SocialLoginService.Outcome outcome = socialLogin.login(token.getAuthorizedClientRegistrationId(),
                user.getSubject(), user.getEmail(), Boolean.TRUE.equals(user.getEmailVerified()), user.getFullName());
        if (outcome.tokens() != null) {
            cookies.write(response, outcome.tokens().accessToken(), outcome.tokens().refreshToken(), false);
            response.sendRedirect("/");
        } else if (outcome.signupToken() != null) {
            cookies.writeSocialSignup(response, outcome.signupToken(), SocialLoginService.SIGNUP_TTL);
            response.sendRedirect("/social-signup.html");
        } else {
            response.sendRedirect("/login.html?social=unverified");
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        endSession(request);
        response.sendRedirect("/login.html?social=failed");
    }

    private static void endSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
