package com.blognm.mainblog.social;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 social.* 값 (003 5단계). 숫자는 모두 환경변수로 바꿀 수 있다 (헌법 VI).
 *
 * @param actionsPerMinute 팔로우·구독·차단을 종류별로 1분에 몇 번까지 (추천안 #11)
 * @param reportCooldown 같은 대상을 다시 신고할 수 있기까지 (SOC-06, 2주)
 * @param notificationRetentionDays 알림 기본 보관 일수 (4.5)
 * @param bioMax 프로필 소개 글자 수
 */
@ConfigurationProperties(prefix = "social")
public record SocialProperties(int actionsPerMinute, Duration reportCooldown, int notificationRetentionDays,
        int bioMax) {
}
