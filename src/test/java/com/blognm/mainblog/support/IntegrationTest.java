package com.blognm.mainblog.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.blognm.mainblog.mail.LogMailGateway;
import com.blognm.mainblog.ratelimit.InMemoryRateLimiter;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.TestConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected LogMailGateway mails;

    @Autowired
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM login_failures");
        jdbc.update("DELETE FROM email_verifications");
        jdbc.update("DELETE FROM members");
        rateLimiter.clear();
        mails.sent().clear();
        clock.reset();
    }

    protected ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** @Async로 보내는 메일을 기다렸다가 6자리 인증번호를 꺼낸다. */
    protected String waitForCode(String email) throws InterruptedException {
        Pattern p = Pattern.compile("인증번호: (\\d{6})");
        for (int i = 0; i < 50; i++) {
            List<LogMailGateway.SentMail> sent = mails.sent();
            for (int j = sent.size() - 1; j >= 0; j--) {
                LogMailGateway.SentMail m = sent.get(j);
                Matcher matcher = p.matcher(m.body());
                if (m.to().equals(email) && matcher.find()) {
                    return matcher.group(1);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("인증 메일이 오지 않았습니다: " + email);
    }

    protected LogMailGateway.SentMail waitForMail(String email) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            for (LogMailGateway.SentMail m : mails.sent()) {
                if (m.to().equals(email)) {
                    return m;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("메일이 오지 않았습니다: " + email);
    }

    /** 가입 세 단계를 끝까지 진행한다. */
    protected void signup(String email, String password, String nickname) throws Exception {
        postJson("/api/auth/signup/code", "{\"email\":\"%s\"}".formatted(email));
        String code = waitForCode(email.toLowerCase());
        postJson("/api/auth/signup/verify", "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code));
        postJson("/api/auth/signup", """
                {"email":"%s","password":"%s","name":"김테스트","nickname":"%s","phone":"010-1234-5678",
                 "agreeTerms":true,"agreePrivacy":true,"age14":true}""".formatted(email, password, nickname));
    }

    @TestConfiguration
    public static class TestConfig {

        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock(Instant.now(), ZoneId.of("Asia/Seoul"));
        }

        /** 테스트를 시작할 때 blog_test를 비우고 마이그레이션을 처음부터 적용한다. */
        @Bean
        public FlywayMigrationStrategy cleanMigrate() {
            return (Flyway flyway) -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }
}
