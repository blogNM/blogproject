package com.blognm.mainblog.stats;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 blog.stats.* 값 (D-112). 숫자는 모두 환경변수로 바꿀 수 있다 (헌법 VI).
 *
 * @param retentionDays 방문 기록을 남겨 두는 날 수. 지나면 새벽 정리 작업이 지운다
 * @param repeatWindow 같은 사람이 같은 화면을 이 시간 안에 다시 열면(새로 고침 등) 한 번으로 센다
 * @param keywordMax 검색어를 저장할 때 최대 글자 수 (DB 칸은 50자)
 * @param maxDayRange 일별 그래프에서 한 번에 고를 수 있는 날 수
 * @param topCount 검색어·유입 사이트·인기 글 목록 개수
 */
@ConfigurationProperties(prefix = "blog.stats")
public record StatsProperties(int retentionDays, Duration repeatWindow, int keywordMax, int maxDayRange,
        int topCount) {

    public StatsProperties {
        if (retentionDays < 1) {
            throw new IllegalStateException("BLOG_STATS_RETENTION_DAYS는 1 이상으로 넣어 주세요.");
        }
        if (keywordMax < 1 || keywordMax > 50) {
            throw new IllegalStateException("BLOG_STATS_KEYWORD_MAX는 1~50 사이로 넣어 주세요.");
        }
        if (maxDayRange < 7) {
            throw new IllegalStateException("BLOG_STATS_MAX_DAY_RANGE는 7 이상으로 넣어 주세요.");
        }
        if (topCount < 1) {
            throw new IllegalStateException("BLOG_STATS_TOP_COUNT는 1 이상으로 넣어 주세요.");
        }
    }
}
