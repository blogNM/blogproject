package com.blognm.mainblog.auth;

/** 관리자 토큰이 아직 쓸 수 있는지 DB로 확인한다 (계정이 있고, 비밀번호를 바꾸기 전에 받은 토큰이 아닌지). */
public interface AdminTokenCheck {

    boolean isValid(JwtProvider.MemberToken token);
}
