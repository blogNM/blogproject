package com.blognm.mainblog.member;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.auth.EmailFindTokenRepository;
import com.blognm.mainblog.auth.LoginService;
import com.blognm.mainblog.auth.RefreshToken;
import com.blognm.mainblog.auth.RefreshTokenRepository;
import com.blognm.mainblog.blog.Blog;
import com.blognm.mainblog.blog.BlogMembershipRepository;
import com.blognm.mainblog.blog.BlogRepository;
import com.blognm.mainblog.blog.JoinRequestRepository;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.DetailedApiException;
import com.blognm.mainblog.common.Hashing;

/** 내 정보 보기·수정 (USR-07)과 회원탈퇴 (USR-05). 모두 본인 것만 다룬다. */
@Service
public class AccountService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final MemberRepository members;
    private final RefreshTokenRepository refreshTokens;
    private final EmailFindTokenRepository findTokens;
    private final BlogRepository blogs;
    private final BlogMembershipRepository memberships;
    private final JoinRequestRepository joinRequests;
    private final LoginService loginService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountService(MemberRepository members, RefreshTokenRepository refreshTokens,
            EmailFindTokenRepository findTokens, BlogRepository blogs, BlogMembershipRepository memberships,
            JoinRequestRepository joinRequests, LoginService loginService, PasswordEncoder passwordEncoder,
            Clock clock) {
        this.members = members;
        this.refreshTokens = refreshTokens;
        this.findTokens = findTokens;
        this.blogs = blogs;
        this.memberships = memberships;
        this.joinRequests = joinRequests;
        this.loginService = loginService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Profile profile(long memberId) {
        Member m = active(memberId);
        return new Profile(m.getEmail(), m.getName(), m.getNickname(), m.getPhone(), m.getCreatedAt().format(DATE));
    }

    /** 닉네임·전화번호만 바꾼다. 이메일·이름은 바꿀 수 없다 (D-21, D-32). */
    @Transactional
    public void updateProfile(long memberId, String rawNickname, String rawPhone) {
        Member m = active(memberId);
        String nickname = MemberRules.checkNickname(rawNickname);
        String phone = MemberRules.normalizePhone(rawPhone);
        if (!nickname.equals(m.getNickname()) && members.existsByNickname(nickname)) {
            throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다.");
        }
        m.changeNickname(nickname);
        m.changePhone(phone);
        try {
            members.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다.");
        }
    }

    /**
     * 현재 비밀번호를 확인하고 바꾼다. 모든 기기의 로그인을 풀고, 지금 쓰는 기기만 새 토큰으로 이어간다 (USR-07, SEC-04).
     *
     * @param currentRefreshToken 지금 기기의 Refresh Token. 로그인 유지 여부를 그대로 이어가는 데만 쓴다.
     */
    @Transactional
    public LoginService.Tokens changePassword(long memberId, String currentPassword, String newPassword,
            String currentRefreshToken) {
        Member m = active(memberId);
        checkPassword(m, currentPassword);
        MemberRules.checkPassword(newPassword);
        boolean rememberMe = currentRefreshToken != null && refreshTokens
                .findByTokenHash(Hashing.sha256Hex(currentRefreshToken))
                .filter(t -> t.getMemberId().equals(memberId))
                .map(RefreshToken::isRememberMe)
                .orElse(false);

        m.changePassword(passwordEncoder.encode(newPassword), LocalDateTime.now(clock));
        refreshTokens.deleteAllByMemberId(memberId);
        findTokens.deleteAllByMemberId(memberId);
        return loginService.reissue(m, rememberMe);
    }

    /**
     * 블로그장인 블로그가 있으면(폐쇄 예정 포함) 탈퇴할 수 없다 (D-15, D-18).
     * 탈퇴하면 로그인 이메일을 바로 비워 같은 이메일로 다시 가입할 수 있다 (USR-05).
     */
    @Transactional
    public void withdraw(long memberId, String password) {
        Member m = active(memberId);
        checkPassword(m, password);
        List<Blog> owned = blogs.findOwned(memberId, List.of(Blog.Status.ACTIVE, Blog.Status.CLOSING));
        if (!owned.isEmpty()) {
            throw new DetailedApiException(HttpStatus.CONFLICT, "OWNS_BLOGS",
                    "블로그장인 블로그가 있어 탈퇴할 수 없습니다. 블로그마다 블로그장을 넘기거나 폐쇄를 끝낸 뒤 탈퇴해 주세요.",
                    Map.of("blogs", owned.stream().map(b -> Map.of("slug", b.getSlug(), "name", b.getName())).toList()));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        m.withdraw(now);
        memberships.leaveAll(memberId, now);
        joinRequests.deletePendingByMember(memberId);
        refreshTokens.deleteAllByMemberId(memberId);
        findTokens.deleteAllByMemberId(memberId);
    }

    private Member active(long memberId) {
        return members.findById(memberId).filter(Member::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "다시 로그인해 주세요."));
    }

    private void checkPassword(Member m, String password) {
        if (password == null || !passwordEncoder.matches(password, m.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "현재 비밀번호가 맞지 않습니다.");
        }
    }

    public record Profile(String email, String name, String nickname, String phone, String joinedAt) {
    }
}
