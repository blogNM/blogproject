package com.blognm.mainblog.member;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.auth.MemberPrincipal;
import com.blognm.mainblog.common.ApiException;

@RestController
public class MeController {

    private final MemberRepository members;

    public MeController(MemberRepository members) {
        this.members = members;
    }

    /** 화면 위쪽의 로그인 상태 표시용. 매번 DB에서 회원 상태를 다시 본다 (헌법 II). */
    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal MemberPrincipal principal) {
        Member member = members.findById(principal.memberId())
                .filter(Member::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "다시 로그인해 주세요."));
        return new MeResponse(member.getId(), member.getNickname());
    }

    public record MeResponse(long id, String nickname) {
    }
}
