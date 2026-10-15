package com.blognm.mainblog.member;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.auth.AuthCookies;
import com.blognm.mainblog.auth.LoginService;
import com.blognm.mainblog.auth.MemberPrincipal;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.social.ProfileService;

@RestController
public class MeController {

    private final MemberRepository members;
    private final AccountService accountService;
    private final AuthCookies cookies;
    private final ProfileService profiles;

    public MeController(MemberRepository members, AccountService accountService, AuthCookies cookies,
            ProfileService profiles) {
        this.members = members;
        this.accountService = accountService;
        this.cookies = cookies;
        this.profiles = profiles;
    }

    /** 화면 위쪽의 로그인 상태 표시용. 매번 DB에서 회원 상태를 다시 본다 (헌법 II). */
    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal MemberPrincipal principal) {
        Member member = members.findById(principal.memberId())
                .filter(Member::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "다시 로그인해 주세요."));
        long id = member.getId();
        return new MeResponse(id, member.getNickname(), member.isTest(), profiles.imageUrlOf(id),
                profiles.followerCount(id), profiles.followingCount(id));
    }

    // ----- 내 정보 (USR-07) -----

    @GetMapping("/api/me/profile")
    public AccountService.Profile profile(@AuthenticationPrincipal MemberPrincipal me) {
        return accountService.profile(me.memberId());
    }

    @PutMapping("/api/me/profile")
    public Map<String, Boolean> updateProfile(@AuthenticationPrincipal MemberPrincipal me,
            @RequestBody ProfileBody body) {
        accountService.updateProfile(me.memberId(), body.nickname(), body.phone());
        return Map.of("updated", true);
    }

    @PostMapping("/api/me/password")
    public Map<String, Boolean> changePassword(@AuthenticationPrincipal MemberPrincipal me,
            @RequestBody PasswordBody body, HttpServletRequest request, HttpServletResponse response) {
        String currentRefresh = AuthCookies.read(request, AuthCookies.REFRESH).orElse(null);
        LoginService.Tokens tokens = accountService.changePassword(me.memberId(), body.currentPassword(),
                body.newPassword(), currentRefresh);
        cookies.write(response, tokens.accessToken(), tokens.refreshToken(), tokens.rememberMe());
        return Map.of("changed", true);
    }

    // ----- 회원탈퇴 (USR-05) -----

    @PostMapping("/api/me/withdraw")
    public Map<String, Boolean> withdraw(@AuthenticationPrincipal MemberPrincipal me, @RequestBody WithdrawBody body,
            HttpServletResponse response) {
        accountService.withdraw(me.memberId(), body.password());
        cookies.clear(response);
        return Map.of("withdrawn", true);
    }

    /** test: 테스트 회원이면 화면 위 닉네임 옆에 "테스트" 표시 (D-113). */
    public record MeResponse(long id, String nickname, boolean test, String imageUrl, long followers, long following) {
    }

    public record ProfileBody(String nickname, String phone) {
    }

    public record PasswordBody(String currentPassword, String newPassword) {
    }

    public record WithdrawBody(String password) {
    }
}
