package com.blognm.mainblog.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.blognm.mainblog.admin.AdminAccountService;
import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 사용 방법 안내 (D-104): 일반 공지 중 제목이 "사용 방법"으로 시작하는 글을 차례대로 넘겨 본다. */
class GuideTest extends IntegrationTest {

    @Autowired
    AdminAccountService accounts;

    @Autowired
    DataSource dataSource;

    private long admin() {
        accounts.run(null);
        return jdbc.queryForObject("SELECT MIN(id) FROM admins", Long.class);
    }

    private void notice(long admin, String title, String category, String status, String content) {
        jdbc.update("INSERT INTO notices (admin_id, title, category, content_md, status) VALUES (?, ?, ?, ?, ?)",
                admin, title, category, content, status);
    }

    private void runGuideMigration() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V13__usage_guides.sql")).execute(dataSource);
    }

    @Test
    @DisplayName("안내 목록은 지우지 않은 일반 공지 중 제목이 '사용 방법'으로 시작하는 글만 번호 순서로, 걸러진 본문과 함께 준다")
    void guidesApi() throws Exception {
        long admin = admin();
        notice(admin, "사용 방법 · 하나", "NOTICE", "NORMAL", "첫 안내");
        notice(admin, "사용 방법 · 업데이트 글", "UPDATE", "NORMAL", "업데이트 카테고리");
        notice(admin, "점검 때 사용 방법", "NOTICE", "NORMAL", "제목 머리가 다름");
        notice(admin, "사용 방법 · 지운 글", "NOTICE", "DELETED", "지움");
        notice(admin, "사용 방법 · 둘", "NOTICE", "NORMAL",
                "![캡처](/img/guides/write.png) ![밖](https://evil.example/x.png) <script>alert(1)</script>");

        // 비회원도 본다
        getAs("/api/notices/guides").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("사용 방법 · 하나"))
                .andExpect(jsonPath("$[0].html").value(Matchers.startsWith("<p>첫 안내</p>")))
                .andExpect(jsonPath("$[1].title").value("사용 방법 · 둘"))
                .andExpect(jsonPath("$[1].html").value(Matchers.containsString("src=\"/img/guides/write.png\"")))
                .andExpect(jsonPath("$[1].html").value(Matchers.not(Matchers.containsString("evil.example"))))
                .andExpect(jsonPath("$[1].html").value(Matchers.not(Matchers.containsString("<script"))));

        // 숫자 공지 주소와 겹치지 않고, 넘겨 보는 화면 주소가 열린다
        getAs("/api/notices/guides").andExpect(jsonPath("$[0].id").isNumber());
        getAs("/guides").andExpect(forwardedUrl("/guide.html"));
    }

    @Test
    @DisplayName("안내 마이그레이션은 관리자가 없으면 아무것도 넣지 않고, 있으면 가장 먼저 만든 관리자 이름으로 안내 6개를 넣는다")
    void migration() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admins", Integer.class)).isZero();
        runGuideMigration();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notices", Integer.class)).isZero();
        getAs("/api/notices/guides").andExpect(jsonPath("$.length()").value(0));

        long admin = admin();
        runGuideMigration();
        List<Long> authors = jdbc.queryForList("SELECT DISTINCT admin_id FROM notices", Long.class);
        assertThat(authors).containsExactly(admin);
        // 새 공지 알림은 보내지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class)).isZero();

        String res = getAs("/api/notices/guides").andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].title").value(Matchers.containsString("테마 틀")))
                .andExpect(jsonPath("$[1].title").value(Matchers.containsString("함께")))
                .andExpect(jsonPath("$[2].title").value(Matchers.containsString("댓글·좋아요·검색")))
                .andReturn().getResponse().getContentAsString();
        List<String> titles = JsonPath.read(res, "$[*].title");
        assertThat(titles).allSatisfy(t -> assertThat(t).startsWith("사용 방법 · ").hasSizeLessThanOrEqualTo(100));

        // 본문의 캡처가 모두 남아 있고, 그 파일이 실제로 있다
        List<String> contents = jdbc.queryForList("SELECT content_md FROM notices", String.class);
        assertThat(contents).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(NoticeService.CONTENT_MAX));
        List<String> htmls = JsonPath.read(res, "$[*].html");
        Matcher m = Pattern.compile("src=\"(/img/guides/[^\"]+)\"").matcher(String.join("\n", htmls));
        int images = 0;
        while (m.find()) {
            images++;
            assertThat(new ClassPathResource("static" + m.group(1)).exists()).as(m.group(1)).isTrue();
        }
        long expected = contents.stream().mapToLong(c -> c.split("\\(/img/guides/", -1).length - 1).sum();
        assertThat(images).isEqualTo((int) expected).isPositive();
    }
}
