package com.blognm.mainblog.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.auth.JwtProvider.SocialSignup;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.member.Member;
import com.blognm.mainblog.member.MemberRepository;
import com.blognm.mainblog.member.MemberRules;

/**
 * 구글 로그인 (D-103)·네이버 로그인 (D-107)·카카오 로그인 (D-108). 세 회사 모두 같은 흐름이다.
 * ① 연결된 계정이면 그 회원으로 로그인 ② 그 회사가 확인한 이메일로 가입한 회원이면 연결하고 로그인
 * ③ 처음 온 사람은 고르는 화면(D-126)에서 (가) 이메일·비밀번호로 기존 계정을 확인해 연결하거나
 * (나) 닉네임·비밀번호·약관 동의를 받아 새로 가입한다. 로그인 뒤에는 이메일 로그인과 같은 토큰 쿠키를 쓴다.
 */
@Service
public class SocialLoginService {

    /** 구글·네이버·카카오에서 돌아와 가입을 마칠 때까지 기다리는 시간. */
    static final Duration SIGNUP_TTL = Duration.ofMinutes(10);

    private final SocialAccountRepository socialAccounts;
    private final MemberRepository members;
    private final LoginService loginService;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public SocialLoginService(SocialAccountRepository socialAccounts, MemberRepository members,
            LoginService loginService, JwtProvider jwtProvider, PasswordEncoder passwordEncoder, Clock clock) {
        this.socialAccounts = socialAccounts;
        this.members = members;
        this.loginService = loginService;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** 구글·네이버·카카오가 돌려준 사용자 정보로 로그인하거나, 가입 화면으로 보낼 토큰을 만든다. */
    @Transactional
    public Outcome login(String provider, String providerUserId, String rawEmail, boolean emailVerified,
            String name) {
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<SocialAccount> linked = socialAccounts.findByProviderAndProviderUserId(provider, providerUserId);
        if (linked.isPresent()) {
            Optional<Member> member = members.findById(linked.get().getMemberId()).filter(Member::isActive);
            if (member.isPresent()) {
                return Outcome.loggedIn(loginService.reissue(member.get(), false));
            }
            // 탈퇴한 회원에 남은 연결은 끊고 처음 온 사람처럼 다룬다.
            socialAccounts.delete(linked.get());
            socialAccounts.flush();
        }

        String email = normalizedOrNull(rawEmail);
        if (email == null || !emailVerified) {
            return Outcome.rejected();
        }
        Optional<Member> sameEmail = members.findByEmail(email).filter(Member::isActive);
        if (sameEmail.isPresent()) {
            Member m = sameEmail.get();
            // 같은 이메일 회원이 이미 같은 회사의 다른 계정과 연결돼 있으면 덮어쓰지 않는다.
            if (socialAccounts.existsByMemberIdAndProvider(m.getId(), provider)) {
                return Outcome.rejected();
            }
            socialAccounts.save(SocialAccount.link(m.getId(), provider, providerUserId, now));
            return Outcome.loggedIn(loginService.reissue(m, false));
        }
        String token = jwtProvider.createSocialSignupToken(
                new SocialSignup(provider, providerUserId, email, name), SIGNUP_TTL);
        return Outcome.needsSignup(token);
    }

    /** 가입 화면에 보여 줄 구글·네이버·카카오 계정 정보. */
    public SocialSignup pending(String signupToken) {
        return parse(signupToken);
    }

    /**
     * 닉네임·비밀번호·약관 동의를 받아 가입을 마치고 로그인한다.
     * 비밀번호를 함께 정해 두어 이메일로도 로그인하고 비밀번호 찾기도 쓸 수 있게 한다 (D-126).
     */
    @Transactional
    public LoginService.Tokens completeSignup(String signupToken, SignupRequest req) {
        SocialSignup s = parse(signupToken);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!req.agreedAll()) {
            throw ApiException.badRequest("CONSENT_REQUIRED", "필수 항목에 모두 동의해 주세요.");
        }
        String nickname = MemberRules.checkNickname(req.nickname());
        MemberRules.checkPassword(req.password());

        // 두 번 눌렀거나 그사이 다른 창에서 끝낸 경우
        Optional<SocialAccount> linked = socialAccounts.findByProviderAndProviderUserId(s.provider(),
                s.providerUserId());
        if (linked.isPresent()) {
            Member m = members.findById(linked.get().getMemberId()).filter(Member::isActive)
                    .orElseThrow(SocialLoginService::expired);
            return loginService.reissue(m, false);
        }
        if (members.existsByEmail(s.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                    "이미 가입된 이메일입니다. " + label(s.provider()) + "로 로그인을 다시 눌러 주세요.");
        }
        if (members.existsByNickname(nickname)) {
            throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다.");
        }
        Member member;
        try {
            member = members.saveAndFlush(Member.join(s.email(), passwordEncoder.encode(req.password()),
                    nameOrNull(s.name()), nickname, null, now));
            socialAccounts.saveAndFlush(SocialAccount.link(member.getId(), s.provider(), s.providerUserId(), now));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SIGNUP_CONFLICT",
                    "가입하지 못했습니다. 닉네임을 바꾸거나 " + label(s.provider()) + "로 로그인을 다시 눌러 주세요.");
        }
        return loginService.reissue(member, false);
    }

    /**
     * 이미 있는 Creat_ 계정에 이 구글·네이버·카카오 계정을 연결하고 그 회원으로 로그인한다 (D-126).
     * 남의 계정에 붙이지 못하게 그 계정의 이메일·비밀번호를 로그인과 똑같이 확인한다 (요청 수 제한·사람 확인·잠금 포함).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginService.Tokens linkExisting(String signupToken, LinkRequest req, String clientIp) {
        SocialSignup s = parse(signupToken);
        LocalDateTime now = LocalDateTime.now(clock);
        Member m = loginService.checkPassword(req.email(), req.password(), req.captchaToken(), clientIp);

        Optional<SocialAccount> linked = socialAccounts.findByProviderAndProviderUserId(s.provider(),
                s.providerUserId());
        if (linked.isPresent()) {
            // 두 번 눌렀으면 그대로 로그인. 그사이 다른 회원으로 가입·연결됐으면 덮어쓰지 않는다.
            if (linked.get().getMemberId().equals(m.getId())) {
                return loginService.reissue(m, false);
            }
            throw new ApiException(HttpStatus.CONFLICT, "SOCIAL_ALREADY_LINKED",
                    "이 " + label(s.provider()) + " 계정은 이미 다른 회원과 연결돼 있어요.");
        }
        if (socialAccounts.existsByMemberIdAndProvider(m.getId(), s.provider())) {
            throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_TAKEN",
                    "이 계정에는 이미 다른 " + label(s.provider()) + " 계정이 연결돼 있어요. 그 "
                            + label(s.provider()) + " 계정으로 로그인해 주세요.");
        }
        try {
            socialAccounts.saveAndFlush(SocialAccount.link(m.getId(), s.provider(), s.providerUserId(), now));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SOCIAL_ALREADY_LINKED",
                    "연결하지 못했습니다. " + label(s.provider()) + "로 로그인을 다시 눌러 주세요.");
        }
        return loginService.reissue(m, false);
    }

    private SocialSignup parse(String signupToken) {
        if (signupToken == null) {
            throw expired();
        }
        return jwtProvider.parseSocialSignupToken(signupToken).orElseThrow(SocialLoginService::expired);
    }

    private static ApiException expired() {
        return ApiException.badRequest("SOCIAL_SIGNUP_EXPIRED",
                "가입 시간이 지났습니다. 로그인 화면에서 구글·네이버·카카오로 로그인을 다시 눌러 주세요.");
    }

    /** 화면 문구에 쓰는 회사 이름 (구글, 네이버, 카카오). */
    public static String label(String provider) {
        if (SocialAccount.NAVER.equals(provider)) {
            return "네이버";
        }
        return SocialAccount.KAKAO.equals(provider) ? "카카오" : "구글";
    }

    private static String normalizedOrNull(String raw) {
        try {
            return MemberRules.normalizeEmail(raw);
        } catch (ApiException e) {
            return null;
        }
    }

    /** 구글·네이버 이름이 이름 규칙(한글·영문 20자)에 맞을 때만 저장한다. 이름은 선택이다. */
    private static String nameOrNull(String raw) {
        try {
            return MemberRules.normalizeName(raw);
        } catch (ApiException e) {
            return null;
        }
    }

    public record SignupRequest(String nickname, String password, Boolean agreeTerms, Boolean agreePrivacy,
            Boolean age14) {

        boolean agreedAll() {
            return Boolean.TRUE.equals(agreeTerms) && Boolean.TRUE.equals(agreePrivacy) && Boolean.TRUE.equals(age14);
        }
    }

    /** 기존 계정 연결: 그 계정의 이메일·비밀번호와, 여러 번 틀렸을 때의 사람 확인 토큰. */
    public record LinkRequest(String email, String password, String captchaToken) {
    }

    /** tokens: 로그인됨, signupToken: 가입 화면으로, 둘 다 없으면 이메일 확인이 안 된 계정이라 거절. */
    public record Outcome(LoginService.Tokens tokens, String signupToken) {

        static Outcome loggedIn(LoginService.Tokens tokens) {
            return new Outcome(tokens, null);
        }

        static Outcome needsSignup(String signupToken) {
            return new Outcome(null, signupToken);
        }

        static Outcome rejected() {
            return new Outcome(null, null);
        }
    }
}
