package com.blognm.mainblog.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치는 처음부터 ShedLock을 건다 (SCL-03, D-12).
 * 서버를 두 대 띄우면 새벽 4시 정리 작업은 app-a에서만 돈다 (app.jobs.enabled, D-111). ShedLock은 그대로 두어
 * 설정을 잘못 넣어 두 대 모두 켜져도 한 번만 돈다.
 */
@Configuration
@EnableAsync
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    /** APP_JOBS_ENABLED=false인 서버는 @Scheduled 작업을 아예 등록하지 않는다. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Jobs {
    }
}
