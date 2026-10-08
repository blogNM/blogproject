package com.blognm.mainblog.auth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.ClientIp;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SignupService signupService;
    private final LoginService loginService;
    private final AuthCookies cookies;
    private final TurnstileVerifier turnstile;

    public AuthController(SignupService signupService, LoginService loginService, AuthCookies cookies,
            TurnstileVerifier turnstile) {
        this.signupService = signupService;
        this.loginService = loginService;
        this.cookies = cookies;
        this.turnstile = turnstile;
    }

    // ----- 회원가입 (USR-01, USR-02) -----

    @PostMapping("/signup/code")
    public Map<String, String> requestSignupCode(@RequestBody EmailBody body, HttpServletRequest request) {
        signupService.requestCode(body.email(), ClientIp.of(request));
        return Map.of("message", "인증번호를 보냈습니다. 메일함을 확인해 주세요.");
    }

    @PostMapping("/signup/verify")
    public Map<String, Boolean> verifySignupCode(@RequestBody VerifyBody body) {
        signupService.verifyCode(body.email(), body.code());
        return Map.of("verified", true);
    }

    @GetMapping("/signup/nickname-available")
    public Map<String, Boolean> nicknameAvailable(@RequestParam String nickname) {
        return Map.of("available", signupService.isNicknameAvailable(nickname));
    }

    @PostMapping("/signup")
    public Map<String, Boolean> signup(@RequestBody SignupService.SignupRequest body) {
        signupService.signup(body);
        return Map.of("joined", true);
    }

    // ----- 로그인·로그아웃 (USR-03, USR-04) -----

    @GetMapping("/login-config")
    public Map<String, String> loginConfig() {
        return Map.of("turnstileSiteKey", turnstile.siteKey());
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginService.LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        LoginService.Tokens tokens = loginService.login(body, ClientIp.of(request));
        cookies.write(response, tokens.accessToken(), tokens.refreshToken(), tokens.rememberMe());
        return Map.of("nickname", tokens.nickname());
    }

    @PostMapping("/refresh")
    public Map<String, String> refresh(@RequestBody(required = false) RefreshBody body, HttpServletRequest request,
            HttpServletResponse response) {
        String raw = AuthCookies.read(request, AuthCookies.REFRESH).orElse(null);
        if (raw == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "다시 로그인해 주세요.");
        }
        try {
            LoginService.Tokens tokens = loginService.refresh(raw, body != null && Boolean.TRUE.equals(body.active()));
            cookies.writeAccess(response, tokens.accessToken(), tokens.rememberMe());
            return Map.of("nickname", tokens.nickname());
        } catch (ApiException e) {
            cookies.clear(response);
            throw e;
        }
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        AuthCookies.read(request, AuthCookies.REFRESH).ifPresent(loginService::logout);
        cookies.clear(response);
        return Map.of("loggedOut", true);
    }

    public record EmailBody(String email) {
    }

    public record VerifyBody(String email, String code) {
    }

    public record RefreshBody(Boolean active) {
    }
}
