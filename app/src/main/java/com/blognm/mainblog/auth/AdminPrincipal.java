package com.blognm.mainblog.auth;

/** 로그인한 메인 관리자. 회원과 다른 계정이라 회원 기능(글쓰기·댓글·팔로우 등)에는 쓰이지 않는다 (SEC-09, D-84). */
public record AdminPrincipal(long adminId) {
}
