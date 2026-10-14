package com.blognm.mainblog.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** 새벽 정리 작업 스위치 (D-111). 서버 두 대 중 app-a만 APP_JOBS_ENABLED=true. DB 없이 스케줄 등록만 본다. */
class JobsSwitchTest {

    @Configuration
    static class OneJob {
        @Bean
        Job job() {
            return new Job();
        }
    }

    static class Job {
        @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
        void run() {
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.Jobs.class, OneJob.class);

    private static int tasks(ApplicationContext ctx) {
        return ctx.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .mapToInt(h -> h.getScheduledTasks().size()).sum();
    }

    @Test
    @DisplayName("값이 없거나 true면 @Scheduled 작업을 등록한다 (서버 한 대·app-a)")
    void enabled() {
        runner.run(ctx -> assertThat(tasks(ctx)).isEqualTo(1));
        runner.withPropertyValues("app.jobs.enabled=true").run(ctx -> assertThat(tasks(ctx)).isEqualTo(1));
    }

    @Test
    @DisplayName("APP_JOBS_ENABLED=false면 @Scheduled 작업을 하나도 등록하지 않는다 (app-b)")
    void disabled() {
        runner.withPropertyValues("app.jobs.enabled=false").run(ctx -> assertThat(tasks(ctx)).isZero());
    }
}
