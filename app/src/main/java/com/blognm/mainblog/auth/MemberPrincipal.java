package com.blognm.mainblog.auth;

/** 로그인한 회원. 토큰에서 꺼낸 회원 번호뿐이고, 나머지는 필요할 때 DB에서 읽는다. */
public record MemberPrincipal(long memberId) {
}
