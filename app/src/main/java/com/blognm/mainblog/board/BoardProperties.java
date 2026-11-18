package com.blognm.mainblog.board;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 board.* 값 (004 4단계). 숫자는 모두 환경변수로 바꿀 수 있다 (헌법 VI).
 *
 * @param reactionsPerMinute 회원 한 명이 1분에 누를 수 있는 좋아요 수 (추천안 #11)
 * @param commentsPerMinute 회원 한 명이 1분에 쓸 수 있는 댓글 수
 * @param popularWindow 피드 인기 글로 볼 기간 (추천안 #15)
 * @param recentSearchMax 회원별 최근 검색어 수 (6.1)
 * @param feedNoticeCount 메인에 보여 줄 공지 수
 * @param commentPageSize 글 화면에서 한 번에 불러오는 댓글 수 (답글은 그 댓글에 함께 붙음, D-131)
 */
@ConfigurationProperties(prefix = "board")
public record BoardProperties(int reactionsPerMinute, int commentsPerMinute, Duration popularWindow,
        int recentSearchMax, int feedNoticeCount, int commentPageSize) {
}
