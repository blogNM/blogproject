package com.blognm.mainblog.auth;

import java.util.List;
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
    private final PasswordResetService passwordResetService;
    private final AuthCookies cookies;
    private final TurnstileVerifier turnstile;

    public AuthController(SignupService signupService, LoginService loginService,
            PasswordResetService passwordResetService, AuthCookies cookies, TurnstileVerifier turnstile) {
        this.signupService = signupService;
        this.loginService = loginService;
        this.passwordResetService = passwordResetService;
        this.cookies = cookies;
        this.turnstile = turnstile;
    }

    // ----- 회원가입 (USR-01, USR-02) -----

    @PostMapping("/signup/code")
    public Map<String, String> requestSignupCode(@RequestBody EmailBody body, HttpServletRequest request) {
        signupService.requestCode(body.email(), ClientIp.of(request));
        return Map.of("message", "인증번호를 보냈습니다. 메일함을 확인해 주세요.");
    }

    @GetMapping("/signup/email-available")
    public Map<String, Boolean> emailAvailable(@RequestParam String email, HttpServletRequest request) {
        return Map.of("available", signupService.isEmailAvailable(email, ClientIp.of(request)));
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

    // ----- 비밀번호 찾기·재설정 (USR-06), 이메일 찾기 (USR-08) -----

    @PostMapping("/password/code")
    public Map<String, String> requestPasswordCode(@RequestBody PasswordCodeBody body, HttpServletRequest request) {
        String masked = passwordResetService.requestCode(body.email(), body.findToken(), ClientIp.of(request));
        return Map.of("message", "입력한 이메일로 안내를 보냈습니다. 메일함을 확인해 주세요.", "maskedEmail", masked);
    }

    @PostMapping("/password/reset")
    public Map<String, Boolean> resetPassword(@RequestBody PasswordResetBody body) {
        passwordResetService.reset(body.email(), body.findToken(), body.code(), body.newPassword());
        return Map.of("changed", true);
    }

    @PostMapping("/find-email")
    public Map<String, List<PasswordResetService.FoundEmail>> findEmail(@RequestBody FindEmailBody body,
            HttpServletRequest request) {
        return Map.of("results", passwordResetService.findEmail(body.name(), body.phone(), ClientIp.of(request)));
    }

    public record PasswordCodeBody(String email, String findToken) {
    }

    public record PasswordResetBody(String email, String findToken, String code, String newPassword) {
    }

    public record FindEmailBody(String name, String phone) {
    }

    public record EmailBody(String email) {
    }

    public record VerifyBody(String email, String code) {
    }

    public record RefreshBody(Boolean active) {
    }
}
