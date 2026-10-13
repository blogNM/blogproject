package com.blognm.mainblog.auth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 구글 로그인 화면용 API (D-103). 구글로 보내고 받는 주소는 스프링 시큐리티가 맡는다. */
@RestController
@RequestMapping("/api/auth/social")
public class SocialAuthController {

    private final SocialLoginService socialLogin;
    private final AuthCookies cookies;
    private final boolean googleEnabled;

    public SocialAuthController(SocialLoginService socialLogin, AuthCookies cookies,
            ObjectProvider<ClientRegistrationRepository> clients) {
        this.socialLogin = socialLogin;
        this.cookies = cookies;
        this.googleEnabled = clients.getIfAvailable() != null;
    }

    /** 로그인 화면이 구글 버튼을 보일지 정한다. */
    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("google", googleEnabled);
    }

    /** 가입 화면에 보여 줄 구글 계정 이메일. */
    @GetMapping("/pending")
    public Map<String, String> pending(HttpServletRequest request) {
        JwtProvider.SocialSignup s = socialLogin.pending(AuthCookies.read(request, AuthCookies.SOCIAL_SIGNUP).orElse(null));
        return Map.of("email", s.email());
    }

    @PostMapping("/signup")
    public Map<String, String> signup(@RequestBody SocialLoginService.SignupRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        LoginService.Tokens tokens = socialLogin.completeSignup(
                AuthCookies.read(request, AuthCookies.SOCIAL_SIGNUP).orElse(null), body);
        cookies.clearSocialSignup(response);
        cookies.write(response, tokens.accessToken(), tokens.refreshToken(), false);
        return Map.of("nickname", tokens.nickname());
    }
}
