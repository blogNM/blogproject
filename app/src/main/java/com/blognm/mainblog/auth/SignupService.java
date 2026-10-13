package com.blognm.mainblog.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.auth.EmailVerification.Purpose;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.config.AppProperties;
import com.blognm.mainblog.mail.AuthMailService;
import com.blognm.mainblog.member.Member;
import com.blognm.mainblog.member.MemberRepository;
import com.blognm.mainblog.member.MemberRules;
import com.blognm.mainblog.ratelimit.RateLimiter;

/** 회원가입: ① 이메일 인증 → ② 비밀번호 → ③ 이름·닉네임·전화번호 (USR-01, USR-02). */
@Service
public class SignupService {

    private final EmailVerificationRepository verifications;
    private final MemberRepository members;
    private final SocialAccountRepository socialAccounts;
    private final AuthMailService mail;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final Clock clock;

    public SignupService(EmailVerificationRepository verifications, MemberRepository members,
            SocialAccountRepository socialAccounts, AuthMailService mail, PasswordEncoder passwordEncoder,
            RateLimiter rateLimiter, AppProperties props, Clock clock) {
        this.verifications = verifications;
        this.members = members;
        this.socialAccounts = socialAccounts;
        this.mail = mail;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 이메일 중복 확인. 가입 화면에서 바로 알려 준다 (D-99). 하나씩 넣어 보는 것을 막으려고 IP별로 센다.
     * 구글·네이버·카카오로 로그인하던 이메일이면 provider로 알려 그 회사로 로그인하라고 안내한다 (D-103, D-107, D-108).
     * google은 예전 화면을 위해 남겨 둔 값이다 (provider가 google일 때 true).
     */
    public EmailStatus checkEmail(String rawEmail, String clientIp) {
        String email = MemberRules.normalizeEmail(rawEmail);
        if (!rateLimiter.tryAcquire("email-check:" + clientIp, props.rateLimit().emailCheckPerMinute(),
                Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        return members.findByEmail(email)
                .map(m -> EmailStatus.taken(socialProvider(m)))
                .orElse(EmailStatus.free());
    }

    /** provider: 이 이메일 회원에 연결된 구글·네이버·카카오 (먼저 연결한 쪽), 없으면 null. */
    public record EmailStatus(boolean available, String provider) {

        static EmailStatus free() {
            return new EmailStatus(true, null);
        }

        static EmailStatus taken(String provider) {
            return new EmailStatus(false, provider);
        }

        public boolean google() {
            return SocialAccount.GOOGLE.equals(provider);
        }
    }

    private String socialProvider(Member member) {
        return socialAccounts.findByMemberIdOrderByIdAsc(member.getId()).stream()
                .map(SocialAccount::getProvider).findFirst().orElse(null);
    }

    /** 인증번호 요청. 이미 가입된 이메일이면 메일을 보내지 않고 바로 알려 준다 (D-99). */
    @Transactional
    public void requestCode(String rawEmail, String clientIp) {
        String email = MemberRules.normalizeEmail(rawEmail);
        LocalDateTime now = LocalDateTime.now(clock);
        AppProperties.Signup cfg = props.signup();
        members.findByEmail(email).ifPresent(m -> {
            throw emailTaken(socialProvider(m));
        });

        verifications.findFirstByEmailAndPurposeOrderByIdDesc(email, Purpose.SIGNUP).ifPresent(prev -> {
            if (prev.getSentAt().plus(cfg.resendInterval()).isAfter(now)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RESEND_TOO_SOON",
                        "인증번호는 1분에 한 번만 다시 보낼 수 있습니다.");
            }
        });
        if (!rateLimiter.tryAcquire("mail:" + clientIp, props.rateLimit().mailPerHour(), Duration.ofHours(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }

        // 다시 보내면 이전 번호는 무효 (USR-02)
        verifications.deleteAllFor(email, Purpose.SIGNUP);

        String code = Hashing.sixDigitCode();
        verifications.save(EmailVerification.issue(email, Purpose.SIGNUP, hashCode(email, code), now,
                now.plus(cfg.codeTtl())));
        mail.sendSignupCode(email, code, cfg.codeTtl().toMinutes());
    }

    private static ApiException emailTaken(String provider) {
        if (provider != null) {
            String label = SocialLoginService.label(provider);
            return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                    label + " 계정으로 가입된 이메일입니다. 로그인 화면에서 " + label + "로 로그인해 주세요.");
        }
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                "이미 가입된 이메일입니다. 로그인하거나 비밀번호 찾기를 이용해 주세요.");
    }

    /** 인증번호 확인. 10분 만료, 5번 틀리면 무효 (USR-02). */
    @Transactional(noRollbackFor = ApiException.class)
    public void verifyCode(String rawEmail, String code) {
        String email = MemberRules.normalizeEmail(rawEmail);
        LocalDateTime now = LocalDateTime.now(clock);
        AppProperties.Signup cfg = props.signup();

        EmailVerification v = verifications.findFirstByEmailAndPurposeOrderByIdDesc(email, Purpose.SIGNUP)
                .filter(x -> !x.isExpired(now))
                .orElseThrow(SignupService::codeInvalid);
        if (v.isVerified()) {
            return;
        }
        if (code == null || !Hashing.constantTimeEquals(v.getCodeHash(), hashCode(email, code.trim()))) {
            int fails = v.recordFailure();
            if (fails >= cfg.maxCodeFailures()) {
                verifications.delete(v);
                throw codeInvalid();
            }
            throw ApiException.badRequest("CODE_MISMATCH",
                    "인증번호가 맞지 않습니다. (%d/%d)".formatted(fails, cfg.maxCodeFailures()));
        }
        v.markVerified(now, now.plus(cfg.verifiedTtl()));
    }

    /** 마지막 단계. 화면을 건너뛰어도 서버가 이메일 인증 완료를 다시 확인한다 (US1-6). */
    @Transactional
    public long signup(SignupRequest req) {
        String email = MemberRules.normalizeEmail(req.email());
        LocalDateTime now = LocalDateTime.now(clock);

        EmailVerification v = verifications.findFirstByEmailAndPurposeOrderByIdDesc(email, Purpose.SIGNUP)
                .filter(x -> x.isVerified() && !x.isExpired(now))
                .orElseThrow(() -> ApiException.badRequest("EMAIL_NOT_VERIFIED",
                        "이메일 인증이 끝나지 않았거나 시간이 지났습니다. 인증번호를 다시 받아 주세요."));

        if (!req.agreedAll()) {
            throw ApiException.badRequest("CONSENT_REQUIRED", "필수 항목에 모두 동의해 주세요.");
        }
        MemberRules.checkPassword(req.password());
        String name = MemberRules.normalizeName(req.name());
        String nickname = MemberRules.checkNickname(req.nickname());
        String phone = MemberRules.normalizePhone(req.phone());

        if (members.existsByNickname(nickname)) {
            throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다.");
        }
        Member member;
        try {
            member = members.saveAndFlush(Member.join(email, passwordEncoder.encode(req.password()), name, nickname,
                    phone, now));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SIGNUP_CONFLICT",
                    "가입하지 못했습니다. 닉네임을 바꾸거나 처음부터 다시 시도해 주세요.");
        }
        // 쓴 인증번호는 바로 지운다 (4.5)
        verifications.delete(v);
        return member.getId();
    }

    public boolean isNicknameAvailable(String rawNickname) {
        String nickname = MemberRules.checkNickname(rawNickname);
        return !members.existsByNickname(nickname);
    }

    private String hashCode(String email, String code) {
        return Hashing.hmacSha256Hex(props.auth().codeHashSecret(), "SIGNUP:" + email + ":" + code);
    }

    private static ApiException codeInvalid() {
        return ApiException.badRequest("CODE_INVALID", "인증번호가 만료되었거나 무효입니다. 인증번호를 다시 받아 주세요.");
    }

    public record SignupRequest(String email, String password, String name, String nickname, String phone,
            Boolean agreeTerms, Boolean agreePrivacy, Boolean age14) {

        boolean agreedAll() {
            return Boolean.TRUE.equals(agreeTerms) && Boolean.TRUE.equals(agreePrivacy) && Boolean.TRUE.equals(age14);
        }
    }
}
