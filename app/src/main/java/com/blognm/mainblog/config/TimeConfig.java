package com.blognm.mainblog.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** 시간은 모두 이 Clock으로 구한다. 테스트에서 시간을 앞으로 돌릴 수 있게 한다. */
    @Bean
    public Clock clock(AppProperties props) {
        return Clock.system(ZoneId.of(props.timeZone()));
    }
}
