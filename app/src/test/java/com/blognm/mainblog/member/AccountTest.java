package com.blognm.mainblog.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

import com.blognm.mainblog.support.IntegrationTest;

/** 001 User Story 5 - 회원정보 수정과 탈퇴 */
class AccountTest extends IntegrationTest {

    private static final String EMAIL = "me@example.com";
    private static final String PW = "Abcd1234!";

    private Cookie[] me;

    @BeforeEach
    void setUp() throws Exception {
        signup(EMAIL, PW, "내닉네임");
        me = login(EMAIL, PW);
    }

    @Test
    @DisplayName("내 정보를 보고 닉네임·전화번호를 바꾼다. 이메일·이름은 바뀌지 않는다")
    void updateProfile() throws Exception {
        getAs("/api/me/profile", me).andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.name").value("김테스트"))
                .andExpect(jsonPath("$.phone").value("01012345678"));

        sendAs(HttpMethod.PUT, "/api/me/profile", """
                {"nickname":"새닉네임","phone":"010-2222-3333","email":"x@x.com","name":"바꿈"}""", me)
                .andExpect(status().isOk());
        getAs("/api/me/profile", me).andExpect(jsonPath("$.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.phone").value("01022223333"))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.name").value("김테스트"));
    }

    @Test
    @DisplayName("위쪽 메뉴용 /api/me에 프로필 사진 주소가 나온다. 사진이 없으면 null (D-119)")
    void meShowsProfileImage() throws Exception {
        getAs("/api/me", me).andExpect(jsonPath("$.nickname").value("내닉네임"))
                .andExpect(jsonPath("$.imageUrl").doesNotExist());

        Long memberId = jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, EMAIL);
        String stored = "11111111-2222-3333-4444-555555555555";
        jdbc.update("""
                INSERT INTO images (uploader_id, stored_name, original_name, mime_type, size_bytes)
                VALUES (?, ?, 'me.png', 'image/png', 100)""", memberId, stored);
        jdbc.update("UPDATE members SET profile_image_id = (SELECT id FROM images WHERE stored_name = ?) WHERE id = ?",
                stored, memberId);
        getAs("/api/me", me).andExpect(jsonPath("$.imageUrl").value("/uploads/" + stored));
    }

    @Test
    @DisplayName("다른 회원이 쓰는 닉네임으로는 바꿀 수 없다")
    void nicknameTaken() throws Exception {
        signup("other@example.com", PW, "남의닉");
        sendAs(HttpMethod.PUT, "/api/me/profile", "{\"nickname\":\"남의닉\",\"phone\":\"01012345678\"}", me)
                .andExpect(status().isConflict());
        // 내 닉네임 그대로는 된다
        sendAs(HttpMethod.PUT, "/api/me/profile", "{\"nickname\":\"내닉네임\",\"phone\":\"01012345678\"}", me)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인한 채로 지금 비밀번호를 계속 맞혀 보면 1시간 동안 막는다 (D-127)")
    void currentPasswordGuessLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            postAs("/api/me/password", "{\"currentPassword\":\"Wrong123!\",\"newPassword\":\"Newpw123!\"}", me)
                    .andExpect(status().isBadRequest());
        }
        postAs("/api/me/password", "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"Newpw123!\"}", me)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        login(EMAIL, PW);
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 바뀌지 않는다")
    void wrongCurrentPassword() throws Exception {
        postAs("/api/me/password", "{\"currentPassword\":\"Wrong123!\",\"newPassword\":\"Newpw123!\"}", me)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        login(EMAIL, PW);
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 다른 기기는 로그아웃되고 지금 기기는 새 토큰으로 이어진다")
    void changePassword() throws Exception {
        Cookie[] other = login(EMAIL, PW);
        clock.advance(Duration.ofSeconds(2));
        MvcResult r = postAs("/api/me/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"Newpw123!\"}"
                .formatted(PW), me).andExpect(status().isOk()).andReturn();
        Cookie newAccess = r.getResponse().getCookie("ACCESS_TOKEN");

        getAs("/api/me", newAccess).andExpect(status().isOk());
        getAs("/api/me", other[0]).andExpect(status().isUnauthorized());
        getAs("/api/me", me[0]).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
        login(EMAIL, "Newpw123!");
    }

    @Test
    @DisplayName("블로그장인 블로그가 있으면 탈퇴할 수 없고 그 블로그 목록을 알려준다")
    void withdrawBlockedByOwnedBlog() throws Exception {
        postAs("/api/blogs", """
                {"slug":"my-blog","name":"내 블로그","visibility":"PUBLIC","joinPolicy":"FREE"}""", me)
                .andExpect(status().isOk());
        postAs("/api/me/withdraw", "{\"password\":\"%s\"}".formatted(PW), me)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNS_BLOGS"))
                .andExpect(jsonPath("$.details.blogs[0].slug").value("my-blog"));
    }

    @Test
    @DisplayName("폐쇄 예정 블로그의 블로그장도 탈퇴할 수 없다 (D-18)")
    void withdrawBlockedByClosingBlog() throws Exception {
        postAs("/api/blogs", """
                {"slug":"closing","name":"닫는 중","visibility":"PRIVATE","joinPolicy":"FREE"}""", me);
        jdbc.update("UPDATE blogs SET status = 'CLOSING' WHERE slug = 'closing'");
        postAs("/api/me/withdraw", "{\"password\":\"%s\"}".formatted(PW), me).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("탈퇴하면 바로 같은 이메일로 다시 가입할 수 있고, 남아 있던 토큰은 쓸 수 없다")
    void withdraw() throws Exception {
        postAs("/api/me/withdraw", "{\"password\":\"Wrong123!\"}", me).andExpect(status().isBadRequest());
        postAs("/api/me/withdraw", "{\"password\":\"%s\"}".formatted(PW), me).andExpect(status().isOk());

        getAs("/api/me", me[0]).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isZero();
        postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, PW))
                .andExpect(status().isUnauthorized());

        mails.sent().clear();
        signup(EMAIL, PW, "다시가입");
        login(EMAIL, PW);
    }

    @Test
    @DisplayName("탈퇴 30일 뒤 배치가 이름·전화번호·비밀번호를 지운다")
    void erasesAfter30Days(@org.springframework.beans.factory.annotation.Autowired
            com.blognm.mainblog.auth.AuthCleanupJob job) throws Exception {
        postAs("/api/me/withdraw", "{\"password\":\"%s\"}".formatted(PW), me).andExpect(status().isOk());
        job.cleanup();
        assertThat(jdbc.queryForObject("SELECT name FROM members WHERE nickname = '내닉네임'", String.class))
                .isEqualTo("김테스트");
        clock.advance(Duration.ofDays(31));
        job.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE nickname = '내닉네임' AND name IS NULL"
                + " AND phone IS NULL AND password_hash IS NULL", Integer.class)).isEqualTo(1);
    }
}
