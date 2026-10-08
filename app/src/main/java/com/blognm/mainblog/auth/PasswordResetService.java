package com.blognm.mainblog.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.auth.EmailVerification.Purpose;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.common.Masking;
import com.blognm.mainblog.config.AppProperties;
import com.blognm.mainblog.mail.AuthMailService;
import com.blognm.mainblog.member.Member;
import com.blognm.mainblog.member.MemberRepository;
import com.blognm.mainblog.member.MemberRules;
import com.blognm.mainblog.ratelimit.RateLimiter;

/** 비밀번호 찾기·재설정 (USR-06, SEC-05)과 이메일 찾기 (USR-08, D-26). */
@Service
public class PasswordResetService {

    private static final DateTimeFormatter JOINED = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final EmailVerificationRepository verifications;
    private final EmailFindTokenRepository findTokens;
    private final MemberRepository members;
    private final RefreshTokenRepository refreshTokens;
    private final LoginFailureRepository loginFailures;
    private final AuthMailService mail;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final Clock clock;

    public PasswordResetService(EmailVerificationRepository verifications, EmailFindTokenRepository findTokens,
            MemberRepository members, RefreshTokenRepository refreshTokens, LoginFailureRepository loginFailures,
            AuthMailService mail, PasswordEncoder passwordEncoder, RateLimiter rateLimiter, AppProperties props,
            Clock clock) {
        this.verifications = verifications;
        this.findTokens = findTokens;
        this.members = members;
        this.refreshTokens = refreshTokens;
        this.loginFailures = loginFailures;
        this.mail = mail;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 인증번호 요청. 이메일을 직접 넣거나, 이메일 찾기 결과의 임시 토큰으로 요청한다.
     * 가입 여부와 관계없이 같은 응답을 준다 (USR-06). 가입 안 된 이메일에는 메일을 보내지 않고,
     * 어떤 6자리로도 맞출 수 없는 행만 만들어 다시 보내기 제한이 똑같이 걸리게 한다.
     *
     * @return 화면에 보여줄 가린 이메일
     */
    @Transactional
    public String requestCode(String rawEmail, String findToken, String clientIp) {
        String email = resolveEmail(rawEmail, findToken);
        LocalDateTime now = LocalDateTime.now(clock);

        verifications.findFirstByEmailAndPurposeOrderByIdDesc(email, Purpose.PASSWORD_RESET).ifPresent(prev -> {
            if (prev.getSentAt().plus(props.signup().resendInterval()).isAfter(now)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RESEND_TOO_SOON",
                        "인증번호는 1분에 한 번만 다시 보낼 수 있습니다.");
            }
        });
        if (!rateLimiter.tryAcquire("mail:" + clientIp, props.rateLimit().mailPerHour(), Duration.ofHours(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        verifications.deleteAllFor(email, Purpose.PASSWORD_RESET);

        boolean registered = members.findByEmail(email).filter(Member::isActive).isPresent();
        String code = Hashing.sixDigitCode();
        String codeHash = hashCode(email, registered ? code : Hashing.randomToken());
        Duration ttl = props.passwordReset().codeTtl();
        verifications.save(EmailVerification.issue(email, Purpose.PASSWORD_RESET, codeHash, now, now.plus(ttl)));
        if (registered) {
            mail.sendPasswordResetCode(email, code, ttl.toMinutes());
        }
        return maskEmail(email);
    }

    /** 인증번호와 새 비밀번호를 한 번에 받는다. 같은 화면에서 새 비밀번호를 정한다 (USR-06). */
    @Transactional(noRollbackFor = ApiException.class)
    public void reset(String rawEmail, String findToken, String code, String newPassword) {
        String email = resolveEmail(rawEmail, findToken);
        LocalDateTime now = LocalDateTime.now(clock);
        int maxFailures = props.passwordReset().maxCodeFailures();
        MemberRules.checkPassword(newPassword);

        EmailVerification v = verifications.findFirstByEmailAndPurposeOrderByIdDesc(email, Purpose.PASSWORD_RESET)
                .filter(x -> !x.isExpired(now))
                .orElseThrow(PasswordResetService::codeInvalid);
        if (code == null || !Hashing.constantTimeEquals(v.getCodeHash(), hashCode(email, code.trim()))) {
            int fails = v.recordFailure();
            if (fails >= maxFailures) {
                verifications.delete(v);
                throw codeInvalid();
            }
            throw ApiException.badRequest("CODE_MISMATCH",
                    "인증번호가 맞지 않습니다. (%d/%d)".formatted(fails, maxFailures));
        }
        Member member = members.findByEmail(email).filter(Member::isActive)
                .orElseThrow(PasswordResetService::codeInvalid);

        member.changePassword(passwordEncoder.encode(newPassword), now);
        // 1회용: 쓴 번호는 바로 지운다. 다른 기기 로그인과 로그인 잠금도 푼다 (USR-06).
        verifications.delete(v);
        refreshTokens.deleteAllByMemberId(member.getId());
        loginFailures.deleteById(email);
        findTokens.deleteAllByMemberId(member.getId());
    }

    /** 이름·전화번호로 가린 이메일과 가입일을 찾는다. 같은 IP는 10분에 5번까지 (USR-08). */
    @Transactional
    public List<FoundEmail> findEmail(String rawName, String rawPhone, String clientIp) {
        AppProperties.EmailFind cfg = props.emailFind();
        if (!rateLimiter.tryAcquire("find-email:" + clientIp, cfg.perWindow(), cfg.window())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "이메일 찾기를 너무 많이 시도했습니다. %d분 뒤에 다시 시도해 주세요.".formatted(cfg.window().toMinutes()));
        }
        String name = MemberRules.normalizeName(rawName);
        String phone = MemberRules.normalizePhone(rawPhone);
        LocalDateTime now = LocalDateTime.now(clock);

        return members.findActiveByNameAndPhone(name, phone).stream().map(m -> {
            String raw = Hashing.randomToken();
            findTokens.save(EmailFindToken.issue(m.getId(), Hashing.sha256Hex(raw), now.plus(cfg.tokenTtl())));
            return new FoundEmail(maskEmail(m.getEmail()), m.getCreatedAt().format(JOINED), raw);
        }).toList();
    }

    private String resolveEmail(String rawEmail, String findToken) {
        if (findToken == null || findToken.isBlank()) {
            return MemberRules.normalizeEmail(rawEmail);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<Member> member = findTokens.findByTokenHash(Hashing.sha256Hex(findToken))
                .filter(t -> !t.isExpired(now))
                .flatMap(t -> members.findById(t.getMemberId()))
                .filter(Member::isActive);
        return member.map(Member::getEmail).orElseThrow(() -> ApiException.badRequest("FIND_TOKEN_EXPIRED",
                "이메일 찾기 결과의 유효 시간(10분)이 지났습니다. 이메일 찾기를 다시 해 주세요."));
    }

    private String hashCode(String email, String code) {
        return Hashing.hmacSha256Hex(props.auth().codeHashSecret(), "PASSWORD_RESET:" + email + ":" + code);
    }

    static String maskEmail(String email) {
        return Masking.email(email);
    }

    private static ApiException codeInvalid() {
        return ApiException.badRequest("CODE_INVALID", "인증번호가 만료되었거나 무효입니다. 인증번호를 다시 받아 주세요.");
    }

    public record FoundEmail(String maskedEmail, String joinedAt, String resetToken) {
    }
}
