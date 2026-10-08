package com.blognm.mainblog.admin;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 계정 (추천안 #16, 확인 대기). 첫 관리자는 서버를 처음 띄울 때 환경변수의 아이디·임시 비밀번호로 만들고,
 * 첫 로그인에서 비밀번호를 바꾸게 한다. 비밀번호는 코드나 DB 스크립트에 두지 않는다.
 */
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(String initialLoginId, String initialPassword, Duration sessionTtl, int loginPerMinute) {
}
