package com.blognm.mainblog.auth;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.DetailedApiException;
import com.blognm.mainblog.config.AppProperties;

/**
 * 로그인 실패를 DB에 세서 사람 확인(Turnstile)과 잠금을 정한다 (SEC-03, SEC-13, D-79, D-114).
 * 회원 로그인과 관리자 로그인이 함께 쓰고, 서버 두 대가 같은 수를 보도록 서버 메모리에는 두지 않는다 (SCL-01).
 *
 * <p>login_failures 한 표에 세 가지를 센다. 회원은 이메일 그대로, 관리자는 "admin:아이디", IP는 "ip:주소".
 * 이메일에는 늘 @가 있고 관리자 아이디·IP에는 없어서 서로 겹치지 않는다.
 * 잠금은 계정에만 건다. IP로 잠그면 학교처럼 같은 IP를 쓰는 사람이 모두 막히기 때문이다.
 *
 * <p>부르는 쪽은 실패를 남긴 뒤 예외를 던지므로 @Transactional(noRollbackFor = ApiException.class) 안에서 부른다.
 */
@Component
public class LoginGuard {

    static final String ADMIN_PREFIX = "admin:";
    static final String IP_PREFIX = "ip:";

    private final LoginFailureRepository failures;
    private final TurnstileVerifier turnstile;
    private final AppProperties.Login cfg;
    private final Clock clock;

    public LoginGuard(LoginFailureRepository failures, TurnstileVerifier turnstile, AppProperties props, Clock clock) {
        this.failures = failures;
        this.turnstile = turnstile;
        this.cfg = props.login();
        this.clock = clock;
    }

    public static String adminKey(String loginId) {
        return ADMIN_PREFIX + loginId;
    }

    /**
     * 비밀번호를 보기 전에 부른다. 잠긴 계정이면 막고, 사람 확인이 필요한데 토큰이 맞지 않으면 막는다.
     * 계정 행은 끝날 때까지 잠가 두어 같은 계정으로 동시에 틀려도 수를 빠뜨리지 않는다.
     */
    public Attempt begin(String accountKey, String clientIp, String turnstileToken) {
        LocalDateTime now = LocalDateTime.now(clock);
        failures.insertIfAbsent(accountKey, now);
        LoginFailure account = failures.findForUpdate(accountKey).orElseThrow();
        account.resetIfLockExpired(now);
        if (account.isLocked(now)) {
            throw locked(account);
        }
        Attempt attempt = new Attempt(account, ipKey(clientIp), now);
        if (needsCaptcha(account.getFailCount(), ipFailCount(attempt)) && !turnstile.verify(turnstileToken, clientIp)) {
            throw new DetailedApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED", "사람인지 확인해 주세요.",
                    captchaDetails(true));
        }
        return attempt;
    }

    /** 틀렸을 때. 계정과 IP 수를 하나씩 올리고, 잠금 또는 "틀렸다"(다음에 사람 확인이 필요한지 함께)를 던진다. */
    public ApiException fail(Attempt attempt, String message) {
        LoginFailure account = attempt.account();
        account.recordFailure(attempt.now(), cfg.lockAfterFailures(), attempt.now().plus(cfg.lockDuration()));
        failures.addRecentFailure(attempt.ipKey(), attempt.now(), attempt.now().minus(cfg.ipWindow()));
        if (account.isLocked(attempt.now())) {
            return locked(account);
        }
        boolean captcha = needsCaptcha(account.getFailCount(), ipFailCount(attempt));
        return new DetailedApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", message, captchaDetails(captcha));
    }

    /** 맞았을 때 계정 수만 지운다. IP 수는 그대로 두어, 자기 계정으로 한 번 로그인해 수를 지우고 다시 시도하지 못하게 한다. */
    public void succeed(Attempt attempt) {
        failures.delete(attempt.account());
    }

    private boolean needsCaptcha(int accountFailures, int ipFailures) {
        return accountFailures >= cfg.captchaAfterFailures() || ipFailures >= cfg.captchaAfterIpFailures();
    }

    private int ipFailCount(Attempt attempt) {
        return failures.recentFailCount(attempt.ipKey(), attempt.now().minus(cfg.ipWindow())).orElse(0);
    }

    private Map<String, Object> captchaDetails(boolean required) {
        return required ? Map.of("captchaRequired", true, "turnstileSiteKey", turnstile.siteKey())
                : Map.of("captchaRequired", false);
    }

    private ApiException locked(LoginFailure failure) {
        return new DetailedApiException(HttpStatus.LOCKED, "LOGIN_LOCKED",
                "로그인에 여러 번 실패해 %d분 동안 로그인할 수 없습니다. 잠시 후 다시 시도해 주세요."
                        .formatted(cfg.lockDuration().toMinutes()),
                Map.of("lockedUntil", failure.getLockedUntil().toString()));
    }

    private static String ipKey(String clientIp) {
        return IP_PREFIX + (clientIp == null ? "" : clientIp);
    }

    public record Attempt(LoginFailure account, String ipKey, LocalDateTime now) {
    }
}
