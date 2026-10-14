package com.blognm.mainblog.testdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * TEST_DATA_ACTION이 있을 때만 켜진다 (D-113). 테스트 데이터를 넣거나(load) 지우고(remove) 앱을 끝낸다.
 * 서버에서는 GitHub Actions의 "테스트 데이터" 워크플로가 운영 중인 이미지로 컨테이너를 하나 더 띄워 이것만 돌린다.
 * 그때는 웹 서버를 띄우지 않으므로(SPRING_MAIN_WEB_APPLICATION_TYPE=none) 운영 중인 두 앱과 포트가 겹치지 않는다.
 */
@Component
@ConditionalOnExpression("'${test-data.action:}' != ''")
public class TestDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestDataRunner.class);

    private final TestDataProperties props;
    private final TestDataSeeder seeder;
    private final TestDataCleaner cleaner;
    private final ConfigurableApplicationContext context;

    public TestDataRunner(TestDataProperties props, TestDataSeeder seeder, TestDataCleaner cleaner,
            ConfigurableApplicationContext context) {
        this.props = props;
        this.seeder = seeder;
        this.cleaner = cleaner;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = 0;
        try {
            switch (props.action().strip().toLowerCase(java.util.Locale.ROOT)) {
                case "load" -> {
                    TestDataSeeder.Result r = seeder.load(props.password(), props.linkPreviews());
                    log.info(r.created()
                            ? "TEST_DATA 결과: 넣음 — 회원 %d명, 블로그 %d개, 글 %d개, 댓글 %d개, 방문 기록 %d건"
                                    .formatted(r.members(), r.blogs(), r.posts(), r.comments(), r.visits())
                            : "TEST_DATA 결과: 이미 있어 더 넣지 않음 — 테스트 회원 %d명의 비밀번호만 바꿈".formatted(r.members()));
                }
                case "remove" -> log.info("TEST_DATA 결과: 지움 — {}", cleaner.remove());
                default -> {
                    log.error("TEST_DATA_ACTION은 load 또는 remove로 넣어 주세요: {}", props.action());
                    code = 2;
                }
            }
        } catch (RuntimeException e) {
            log.error("TEST_DATA 실패: {}", e.getMessage(), e);
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
