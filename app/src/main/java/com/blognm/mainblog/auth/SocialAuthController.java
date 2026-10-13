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

/** 구글(D-103)·네이버(D-107)·카카오(D-108) 로그인 화면용 API. 바깥 로그인 화면으로 보내고 받는 주소는 스프링 시큐리티가 맡는다. */
@RestController
@RequestMapping("/api/auth/social")
public class SocialAuthController {

    private final SocialLoginService socialLogin;
    private final AuthCookies cookies;
    private final boolean googleEnabled;
    private final boolean naverEnabled;
    private final boolean kakaoEnabled;

    public SocialAuthController(SocialLoginService socialLogin, AuthCookies cookies,
            ObjectProvider<ClientRegistrationRepository> clients) {
        this.socialLogin = socialLogin;
        this.cookies = cookies;
        ClientRegistrationRepository repo = clients.getIfAvailable();
        this.googleEnabled = repo != null && repo.findByRegistrationId(SocialAccount.GOOGLE) != null;
        this.naverEnabled = repo != null && repo.findByRegistrationId(SocialAccount.NAVER) != null;
        this.kakaoEnabled = repo != null && repo.findByRegistrationId(SocialAccount.KAKAO) != null;
    }

    /** 로그인 화면이 구글·네이버·카카오 버튼을 보일지 정한다. */
    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("google", googleEnabled, "naver", naverEnabled, "kakao", kakaoEnabled);
    }

    /** 가입 화면에 보여 줄 계정 이메일과 회사 (google|naver|kakao). */
    @GetMapping("/pending")
    public Map<String, String> pending(HttpServletRequest request) {
        JwtProvider.SocialSignup s = socialLogin.pending(AuthCookies.read(request, AuthCookies.SOCIAL_SIGNUP).orElse(null));
        return Map.of("email", s.email(), "provider", s.provider());
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
