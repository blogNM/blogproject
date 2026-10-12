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
        Turnstile turnstile) {

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

    public record Login(int captchaAfterFailures, int lockAfterFailures, Duration lockDuration) {
    }

    public record RateLimit(int loginPerMinute, int mailPerHour, int emailCheckPerMinute) {
    }

    public record Turnstile(String siteKey, String secretKey, String verifyUrl) {
    }
}
