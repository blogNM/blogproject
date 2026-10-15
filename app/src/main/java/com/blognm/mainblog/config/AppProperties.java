package com.blognm.mainblog.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 app.* 값. 숫자는 모두 환경변수로 바꿀 수 있다 (헌법 VI). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String timeZone,
        String baseUrl,
        Mail mail,
        Auth auth,
        Signup signup,
        PasswordReset passwordReset,
        EmailFind emailFind,
        Login login,
        RateLimit rateLimit,
        Recaptcha recaptcha) {

    public record Mail(String mode, String from) {
    }

    public record Auth(
            String jwtSecret,
            Duration accessTokenTtl,
            Duration rememberMeTtl,
            Duration idleTimeout,
            Duration sessionMaxTtl,
            boolean cookieSecure,
            String codeHashSecret) {
    }

    public record Signup(Duration codeTtl, Duration verifiedTtl, Duration resendInterval, int maxCodeFailures) {
    }

    public record PasswordReset(Duration codeTtl, int maxCodeFailures) {
    }

    public record EmailFind(Duration tokenTtl, int perWindow, Duration window) {
    }

    /**
     * 로그인 실패 (SEC-03, SEC-13). 계정(회원 이메일·관리자 아이디)마다 captchaAfterFailures번 틀리면 사람 확인,
     * lockAfterFailures번이면 잠금. 같은 IP에서 ipWindow 안에 계정을 바꿔 가며 captchaAfterIpFailures번 틀려도 사람 확인 (D-115).
     */
    public record Login(int captchaAfterFailures, int lockAfterFailures, Duration lockDuration,
            int captchaAfterIpFailures, Duration ipWindow) {
    }

    public record RateLimit(int loginPerMinute, int mailPerHour, int emailCheckPerMinute) {
    }

    public record Recaptcha(String siteKey, String secretKey, String verifyUrl) {
    }
}
