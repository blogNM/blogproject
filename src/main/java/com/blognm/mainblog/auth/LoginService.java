package com.blognm.mainblog.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.DetailedApiException;
import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.config.AppProperties;
import com.blognm.mainblog.member.Member;
import com.blognm.mainblog.member.MemberRepository;
import com.blognm.mainblog.member.MemberRules;
import com.blognm.mainblog.ratelimit.RateLimiter;

/** 로그인·토큰 갱신·로그아웃 (USR-03, USR-04, SEC-03, SEC-04, SEC-13). */
@Service
public class LoginService {

    /** 가입 안 된 이메일도 비밀번호 확인에 같은 시간이 걸리게 하는 더미 해시. */
    private final String dummyHash;

    private final MemberRepository members;
    private final LoginFailureRepository failures;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final TurnstileVerifier turnstile;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final Clock clock;

    public LoginService(MemberRepository members, LoginFailureRepository failures,
            RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder, JwtProvider jwtProvider,
            TurnstileVerifier turnstile, RateLimiter rateLimiter, AppProperties props, Clock clock) {
        this.members = members;
        this.failures = failures;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.turnstile = turnstile;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(Hashing.randomToken());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Tokens login(LoginRequest req, String clientIp) {
        if (!rateLimiter.tryAcquire("login:" + clientIp, props.rateLimit().loginPerMinute(), Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        String email = normalizeOrFail(req.email());
        LocalDateTime now = LocalDateTime.now(clock);
        AppProperties.Login cfg = props.login();

        failures.insertIfAbsent(email, now);
        LoginFailure failure = failures.findForUpdate(email).orElseThrow();
        failure.resetIfLockExpired(now);
        if (failure.isLocked(now)) {
            throw locked(failure);
        }
        if (failure.getFailCount() >= cfg.captchaAfterFailures() && !turnstile.verify(req.turnstileToken(), clientIp)) {
            throw new DetailedApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED", "사람인지 확인해 주세요.",
                    Map.of("captchaRequired", true, "turnstileSiteKey", turnstile.siteKey()));
        }

        Optional<Member> member = members.findByEmail(email).filter(Member::isActive);
        String hash = member.map(Member::getPasswordHash).orElse(dummyHash);
        boolean matches = req.password() != null && passwordEncoder.matches(req.password(), hash);
        if (member.isEmpty() || !matches) {
            failure.recordFailure(now, cfg.lockAfterFailures(), now.plus(cfg.lockDuration()));
            if (failure.isLocked(now)) {
                throw locked(failure);
            }
            boolean captcha = failure.getFailCount() >= cfg.captchaAfterFailures();
            throw new DetailedApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED",
                    "이메일 또는 비밀번호가 맞지 않습니다.",
                    captcha ? Map.of("captchaRequired", true, "turnstileSiteKey", turnstile.siteKey())
                            : Map.of("captchaRequired", false));
        }

        failures.delete(failure);
        return issue(member.get(), req.remember(), now);
    }

    /**
     * Access Token 다시 발급. active는 화면에서 사용자가 직접 클릭·입력했는지다.
     * 알림 폴링 같은 자동 요청은 active=false로 와서 30분 무활동 시간을 늘리지 않는다 (D-62).
     */
    @Transactional
    public Tokens refresh(String rawRefreshToken, boolean active) {
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = Hashing.sha256Hex(rawRefreshToken);
        RefreshToken token = refreshTokens.findByTokenHash(hash).orElseThrow(LoginService::loginRequired);
        boolean idle = !token.isRememberMe()
                && token.getLastActivityAt().plus(props.auth().idleTimeout()).isBefore(now);
        if (token.isExpired(now) || idle) {
            refreshTokens.delete(token);
            throw loginRequired();
        }
        Member member = members.findById(token.getMemberId()).filter(Member::isActive)
                .orElseThrow(LoginService::loginRequired);
        if (active) {
            token.touch(now);
        }
        return new Tokens(jwtProvider.createMemberToken(member.getId()), rawRefreshToken, token.isRememberMe(),
                member.getNickname());
    }

    /** 서버에서 Refresh Token을 폐기한다 (USR-04). */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.deleteByTokenHash(Hashing.sha256Hex(rawRefreshToken));
    }

    private Tokens issue(Member member, boolean rememberMe, LocalDateTime now) {
        String raw = Hashing.randomToken();
        Duration ttl = rememberMe ? props.auth().rememberMeTtl() : props.auth().sessionMaxTtl();
        refreshTokens.save(RefreshToken.forMember(member.getId(), Hashing.sha256Hex(raw), rememberMe, now,
                now.plus(ttl)));
        return new Tokens(jwtProvider.createMemberToken(member.getId()), raw, rememberMe, member.getNickname());
    }

    private static String normalizeOrFail(String rawEmail) {
        try {
            return MemberRules.normalizeEmail(rawEmail);
        } catch (ApiException e) {
            throw new DetailedApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED",
                    "이메일 또는 비밀번호가 맞지 않습니다.", Map.of("captchaRequired", false));
        }
    }

    private ApiException locked(LoginFailure failure) {
        return new DetailedApiException(HttpStatus.LOCKED, "LOGIN_LOCKED",
                "로그인에 여러 번 실패해 %d분 동안 로그인할 수 없습니다. 잠시 후 다시 시도해 주세요."
                        .formatted(props.login().lockDuration().toMinutes()),
                Map.of("lockedUntil", failure.getLockedUntil().toString()));
    }

    private static ApiException loginRequired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "다시 로그인해 주세요.");
    }

    public record LoginRequest(String email, String password, Boolean rememberMe, String turnstileToken) {

        boolean remember() {
            return Boolean.TRUE.equals(rememberMe);
        }
    }

    public record Tokens(String accessToken, String refreshToken, boolean rememberMe, String nickname) {
    }
}
