package com.blognm.mainblog.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 테스트에서 시간을 앞으로 돌리는 시계. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant now;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void reset() {
        now = Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
