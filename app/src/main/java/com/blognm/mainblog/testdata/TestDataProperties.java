package com.blognm.mainblog.testdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 test-data.* 값 (D-113). 실제 사이트에 테스트 회원과 그 활동을 넣고 지운다.
 *
 * @param action load(넣기) 또는 remove(지우기). 값이 있으면 웹 서버 없이 그 일만 하고 끝난다. 평소에는 비어 있다
 * @param password 테스트 회원 비밀번호. 저장소에 두지 않고 GitHub 시크릿 TEST_MEMBER_PASSWORD로만 받는다
 * @param linkPreviews 글에 넣은 링크의 미리보기 카드(D-92)를 바깥 사이트에서 받을지
 */
@ConfigurationProperties(prefix = "test-data")
public record TestDataProperties(String action, String password, boolean linkPreviews) {
}
