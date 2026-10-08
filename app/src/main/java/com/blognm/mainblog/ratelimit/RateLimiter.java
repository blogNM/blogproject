package com.blognm.mainblog.ratelimit;

import java.time.Duration;

/**
 * IP별 요청 제한 (D-80). 저장소를 이 인터페이스 뒤에 두어, 서버를 늘릴 때
 * 메모리 구현을 Redis 같은 공용 저장소 구현으로 바꾼다 (헌법 V, 추천안 #2 / D-83 대기).
 */
public interface RateLimiter {

    /** key로 window 안에 limit번까지 허용한다. 넘으면 false. */
    boolean tryAcquire(String key, int limit, Duration window);
}
