package com.blognm.mainblog.linkpreview;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 link-preview.* 값 (D-92). 숫자는 모두 환경변수로 바꿀 수 있다 (헌법 VI).
 *
 * @param enabled 끄면 링크는 글자 링크로만 보인다
 * @param timeout 바깥 사이트 한 번 요청에 기다리는 시간
 * @param maxLinksPerPost 글 하나를 저장할 때 미리보기를 받는 링크 수
 * @param refreshAfter 받아 둔 미리보기를 다시 받기까지
 * @param allowPrivateHosts 내부망 주소 허용. 테스트에서만 켠다 (SSRF 막기)
 * @param collectBudget 글 하나를 저장할 때 미리보기를 받는 데 쓰는 시간을 모두 합친 상한. 넘으면 남은 링크는 다음 저장 때 받는다
 */
@ConfigurationProperties(prefix = "link-preview")
public record LinkPreviewProperties(boolean enabled, Duration timeout, int maxLinksPerPost, Duration refreshAfter,
        boolean allowPrivateHosts, Duration collectBudget) {
}
