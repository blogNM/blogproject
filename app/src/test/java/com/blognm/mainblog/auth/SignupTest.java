package com.blognm.mainblog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.blognm.mainblog.support.IntegrationTest;

/** 001 User Story 1 - 이메일 인증으로 가입하기 */
class SignupTest extends IntegrationTest {

    private static final String SIGNUP_BODY = """
            {"email":"%s","password":"Abcd1234!","name":"김테스트","nickname":"%s","phone":"010-1234-5678",
             "agreeTerms":%s,"agreePrivacy":true,"age14":true}""";

    @Test
    @DisplayName("세 단계를 끝까지 하면 가입되고, 비밀번호는 bcrypt로 저장되며 인증번호 행은 지워진다")
    void fullSignup() throws Exception {
        postJson("/api/auth/signup/code", "{\"email\":\"New@Example.com\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("인증번호를 보냈습니다. 메일함을 확인해 주세요."));
        String code = waitForCode("new@example.com");

        postJson("/api/auth/signup/verify", "{\"email\":\"new@example.com\",\"code\":\"%s\"}".formatted(code))
                .andExpect(status().isOk());
        postJson("/api/auth/signup", SIGNUP_BODY.formatted("new@example.com", "새회원", true))
                .andExpect(status().isOk());

        var row = jdbc.queryForMap("SELECT email, password_hash, phone, name FROM members WHERE nickname = '새회원'");
        assertThat(row.get("email")).isEqualTo("new@example.com");
        assertThat((String) row.get("password_hash")).startsWith("$2").doesNotContain("Abcd1234!");
        assertThat(row.get("phone")).isEqualTo("01012345678");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verifications", Integer.class)).isZero();
    }

    @Test
    @DisplayName("중복 확인 버튼: 가입된 이메일이면 화면에서 바로 알려 주고, 인증번호 메일도 보내지 않는다 (D-99)")
    void emailTaken() throws Exception {
        signup("old@example.com", "Abcd1234!", "기존회원");
        mails.sent().clear();
        clock.advance(Duration.ofMinutes(2));

        mvc.perform(get("/api/auth/signup/email-available").param("email", " OLD@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.google").value(false));
        mvc.perform(get("/api/auth/signup/email-available").param("email", "fresh@example.com"))
                .andExpect(jsonPath("$.available").value(true));
        mvc.perform(get("/api/auth/signup/email-available").param("email", "not-an-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EMAIL"));

        postJson("/api/auth/signup/code", "{\"email\":\"OLD@example.com\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        assertThat(mails.sent()).isEmpty();
    }

    @Test
    @DisplayName("인증을 건너뛰고 마지막 단계를 직접 보내면 거절된다")
    void skipVerificationRejected() throws Exception {
        postJson("/api/auth/signup", SIGNUP_BODY.formatted("skip@example.com", "건너뛰기", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        // 인증번호만 받고 확인하지 않은 경우도 같다
        postJson("/api/auth/signup/code", "{\"email\":\"skip@example.com\"}");
        waitForCode("skip@example.com");
        postJson("/api/auth/signup", SIGNUP_BODY.formatted("skip@example.com", "건너뛰기", true))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("5번 틀리면 인증번호가 무효가 된다")
    void fiveWrongCodes() throws Exception {
        postJson("/api/auth/signup/code", "{\"email\":\"wrong@example.com\"}");
        String code = waitForCode("wrong@example.com");
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 4; i++) {
            postJson("/api/auth/signup/verify", "{\"email\":\"wrong@example.com\",\"code\":\"%s\"}".formatted(wrong))
                    .andExpect(jsonPath("$.code").value("CODE_MISMATCH"));
        }
        postJson("/api/auth/signup/verify", "{\"email\":\"wrong@example.com\",\"code\":\"%s\"}".formatted(wrong))
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
        postJson("/api/auth/signup/verify", "{\"email\":\"wrong@example.com\",\"code\":\"%s\"}".formatted(code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    @Test
    @DisplayName("10분이 지나면 인증번호가 무효가 된다")
    void codeExpires() throws Exception {
        postJson("/api/auth/signup/code", "{\"email\":\"late@example.com\"}");
        String code = waitForCode("late@example.com");
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        postJson("/api/auth/signup/verify", "{\"email\":\"late@example.com\",\"code\":\"%s\"}".formatted(code))
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    @Test
    @DisplayName("1분 안에 다시 보내면 거절되고, 1분 뒤 다시 보내면 이전 번호는 무효가 된다")
    void resend() throws Exception {
        postJson("/api/auth/signup/code", "{\"email\":\"again@example.com\"}").andExpect(status().isOk());
        String first = waitForCode("again@example.com");

        postJson("/api/auth/signup/code", "{\"email\":\"again@example.com\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RESEND_TOO_SOON"));

        clock.advance(Duration.ofSeconds(61));
        mails.sent().clear();
        postJson("/api/auth/signup/code", "{\"email\":\"again@example.com\"}").andExpect(status().isOk());
        String second = waitForCode("again@example.com");

        if (!first.equals(second)) {
            postJson("/api/auth/signup/verify",
                    "{\"email\":\"again@example.com\",\"code\":\"%s\"}".formatted(first))
                    .andExpect(jsonPath("$.code").value("CODE_MISMATCH"));
        }
        postJson("/api/auth/signup/verify", "{\"email\":\"again@example.com\",\"code\":\"%s\"}".formatted(second))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("필수 동의를 하나라도 안 하면 가입되지 않는다")
    void consentRequired() throws Exception {
        postJson("/api/auth/signup/code", "{\"email\":\"agree@example.com\"}");
        String code = waitForCode("agree@example.com");
        postJson("/api/auth/signup/verify", "{\"email\":\"agree@example.com\",\"code\":\"%s\"}".formatted(code));

        postJson("/api/auth/signup", SIGNUP_BODY.formatted("agree@example.com", "동의안함", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isZero();
    }

    @Test
    @DisplayName("닉네임이 겹치면 가입되지 않는다")
    void duplicateNickname() throws Exception {
        signup("first@example.com", "Abcd1234!", "같은닉");
        postJson("/api/auth/signup/code", "{\"email\":\"second@example.com\"}");
        String code = waitForCode("second@example.com");
        postJson("/api/auth/signup/verify", "{\"email\":\"second@example.com\",\"code\":\"%s\"}".formatted(code));
        postJson("/api/auth/signup", SIGNUP_BODY.formatted("second@example.com", "같은닉", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));
    }

    @Test
    @DisplayName("같은 IP에서 인증 메일은 1시간에 10통까지만 보낸다")
    void mailRateLimitPerIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            postJson("/api/auth/signup/code", "{\"email\":\"user%d@example.com\"}".formatted(i))
                    .andExpect(status().isOk());
        }
        postJson("/api/auth/signup/code", "{\"email\":\"user10@example.com\"}")
                .andExpect(status().isTooManyRequests());
    }
}
