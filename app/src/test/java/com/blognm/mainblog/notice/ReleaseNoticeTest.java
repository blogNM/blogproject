package com.blognm.mainblog.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.blognm.mainblog.admin.AdminAccountService;
import com.blognm.mainblog.post.MarkdownRenderer;
import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 깃허브 릴리스를 메인 공지로 올린다: 토큰이 맞아야 하고, 같은 버전은 한 번만 (D-97). */
class ReleaseNoticeTest extends IntegrationTest {

    @Autowired
    AdminAccountService accounts;

    @BeforeEach
    void setUp() throws Exception {
        accounts.run(null);
    }

    private ResultActions publish(String token, String json) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/release-notices").contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            req.header("X-Release-Token", token);
        }
        return mvc.perform(req);
    }

    @Test
    @DisplayName("토큰이 맞으면 CSRF 없이 공지가 올라가고 회원에게 알림이 가며, 같은 버전을 다시 보내면 새로 쓰지 않고 본문만 바꾼다")
    void publishOnce() throws Exception {
        member("a@example.com", "회원에이");
        String body = """
                {"title":"Creat_ v1.0.0 업데이트 소식","content":"![카드](/img/releases/v1.0.0/link-card.png) ![밖](https://example.com/a.png)"}""";

        publish(null, body).andExpect(status().isForbidden());
        publish("wrong", body).andExpect(status().isForbidden());

        publish("test-release-token", """
                {"title":"Creat_ v1.0.0 업데이트 소식","content":"처음 노트"}""").andExpect(status().isOk());
        publish("test-release-token", body).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notices", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'NOTICE'", Integer.class))
                .isEqualTo(1);
        long id = jdbc.queryForObject("SELECT id FROM notices", Long.class);
        // 릴리스 캡처(/img/releases/)는 본문에 남고, 바깥 사진은 걸러진다
        String html = JsonPath.read(getAs("/api/notices/" + id).andReturn().getResponse().getContentAsString(), "$.html");
        assertThat((String) JsonPath.read(getAs("/api/notices/" + id).andReturn().getResponse().getContentAsString(),
                "$.category")).isEqualTo("UPDATE");
        assertThat(html).contains("src=\"/img/releases/v1.0.0/link-card.png\"").doesNotContain("example.com");
    }

    @Test
    @DisplayName("릴리스 노트 강조(인용·굵게·밑줄·형광펜·글자 크기)는 사이트 공지에서 지워지지 않는다 (D-109 노트 형식)")
    void releaseNoteEmphasisSurvives() {
        String html = new MarkdownRenderer().toSafeHtml("""
                > 고친 것: <span class="hl-yellow">약 2MB</span>를 한 번만 받아요. <u>결과는 그대로</u>

                > <span class="ts-large">새로고침이 약 <span class="hl-yellow">6배</span> 빨라졌어요.</span> **굵게**
                """);
        assertThat(html).contains("<blockquote>")
                .contains("<span class=\"hl-yellow\">약 2MB</span>")
                .contains("<u>결과는 그대로</u>")
                .contains("<span class=\"ts-large\">새로고침이 약 <span class=\"hl-yellow\">6배</span> 빨라졌어요.</span>")
                .contains("<strong>굵게</strong>");
    }
}
