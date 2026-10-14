package com.blognm.mainblog.testdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.blognm.mainblog.admin.AdminAccountService;
import com.blognm.mainblog.image.UploadProperties;
import com.blognm.mainblog.post.PostRules;
import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 실제 사이트용 테스트 회원 넣기·지우기와 "테스트" 표시 (D-113). */
class TestDataTest extends IntegrationTest {

    private static final String PASSWORD = "Seed1234!";

    /** 데이터가 있는 표 전부. 다시 넣어도, 지워도 수를 비교한다. */
    private static final List<String> TABLES = List.of("members", "blogs", "blog_memberships", "join_requests",
            "ownership_transfers", "member_sanctions", "categories", "posts", "post_tags", "images", "comments",
            "post_likes", "post_views", "follows", "subscriptions", "blocks", "reports", "notifications",
            "notification_settings", "blog_visits", "blog_tags", "tags");

    @Autowired
    private TestDataSeeder seeder;

    @Autowired
    private TestDataCleaner cleaner;

    @Autowired
    private UploadProperties uploads;

    @Autowired
    private AdminAccountService adminAccounts;

    private TestDataSeeder.Result load() {
        return seeder.load(PASSWORD, false);
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        TABLES.forEach(t -> out.put(t, id("SELECT COUNT(*) FROM " + t)));
        return out;
    }

    @Test
    @DisplayName("넣기: 테스트 회원 8명이 블로그·글·사진·댓글·신고·통계까지 실제 기능으로 남긴다")
    void loadsRichData() throws Exception {
        TestDataSeeder.Result r = load();
        assertThat(r.created()).isTrue();

        assertThat(id("SELECT COUNT(*) FROM members WHERE is_test = TRUE AND email LIKE '%@test.creat'")).isEqualTo(8);
        assertThat(id("SELECT COUNT(*) FROM members WHERE is_test = TRUE AND nickname LIKE '테스트%'")).isEqualTo(8);
        assertThat(jdbc.queryForList("SELECT DISTINCT theme FROM blogs", String.class)).hasSize(5);
        assertThat(jdbc.queryForList("SELECT DISTINCT visibility FROM blogs", String.class))
                .containsExactlyInAnyOrder("PUBLIC", "LINK_ONLY", "PRIVATE");
        // 본문 최대 글자 수를 꽉 채운 글
        List<String> bodies = jdbc.queryForList("SELECT content_md FROM posts", String.class);
        assertThat(bodies.stream().mapToInt(PostRules::contentLength).max().orElse(0)).isEqualTo(PostRules.CONTENT_MAX);
        // 사진은 실제 올리기와 같은 저장소에 파일로 들어간다
        List<String> files = jdbc.queryForList("SELECT stored_name FROM images", String.class);
        // 링크 미리보기 카드: 테스트 글에만 있던 주소는 지우고, 다른 주소는 남긴다
        for (String url : List.of("https://vimeo.com/1084537", "https://example.org/real")) {
            jdbc.update("""
                    INSERT INTO link_previews (url_hash, url, status, fetched_at) VALUES (SHA2(?, 256), ?, 'FAILED', NOW())
                    """, url, url);
        }
        assertThat(files).hasSizeGreaterThan(10);
        files.forEach(f -> assertThat(Files.exists(Path.of(uploads.dir(), f))).isTrue());
        assertThat(id("SELECT COUNT(*) FROM images WHERE post_id IS NOT NULL")).isGreaterThanOrEqualTo(6);
        assertThat(id("SELECT COUNT(*) FROM posts WHERE content_md LIKE '%youtube.com%'")).isGreaterThan(0);
        assertThat(id("SELECT COUNT(*) FROM posts WHERE content_md LIKE '%vimeo.com%'")).isGreaterThan(0);
        assertThat(id("SELECT COUNT(*) FROM posts WHERE content_md LIKE '%- [x]%'")).isGreaterThan(0);
        assertThat(id("SELECT COUNT(*) FROM posts WHERE is_notice = TRUE")).isEqualTo(2);
        // 부블로그장 권한이 서로 다르고, 참여 신청은 대기·승인·거절이 모두 있다
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(perm_edit_info, perm_manage_members, perm_manage_posts) FROM blog_memberships
                WHERE role = 'SUB_OWNER'""", String.class)).containsExactlyInAnyOrder("111", "010", "101");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM join_requests", String.class))
                .contains("PENDING", "APPROVED", "REJECTED");
        assertThat(id("SELECT COUNT(*) FROM ownership_transfers WHERE status = 'PENDING'")).isEqualTo(1);
        // 신고는 블로그장 접수와 관리자 접수가 모두, 사유는 여러 가지 (D-85)
        assertThat(jdbc.queryForList("SELECT DISTINCT handler FROM reports", String.class))
                .containsExactlyInAnyOrder("BLOG_OWNER", "ADMIN");
        assertThat(id("SELECT COUNT(DISTINCT reason_code) FROM reports")).isGreaterThanOrEqualTo(5);
        assertThat(id("SELECT COUNT(*) FROM member_sanctions WHERE type = 'WARNING'")).isEqualTo(1);
        assertThat(id("SELECT COUNT(*) FROM comments WHERE parent_id IS NOT NULL")).isGreaterThan(3);
        assertThat(id("SELECT COUNT(*) FROM post_likes")).isGreaterThan(20);
        assertThat(id("SELECT COUNT(*) FROM follows")).isGreaterThan(5);
        assertThat(id("SELECT COUNT(*) FROM subscriptions")).isGreaterThan(5);
        assertThat(id("SELECT COUNT(*) FROM notifications")).isGreaterThan(10);
        assertThat(id("SELECT SUM(view_count) FROM posts")).isGreaterThan(50);
        // 블로그 통계용 방문 기록 6주치, 경로와 검색어가 섞여 있다 (D-112)
        assertThat(r.visits()).isGreaterThan(1000);
        assertThat(id("SELECT DATEDIFF(MAX(visited_at), MIN(visited_at)) FROM blog_visits"))
                .isGreaterThanOrEqualTo(TestDataSeeder.VISIT_DAYS - 1L);
        assertThat(id("SELECT COUNT(DISTINCT source) FROM blog_visits")).isGreaterThan(8);
        assertThat(id("SELECT COUNT(*) FROM blog_visits WHERE keyword IS NOT NULL")).isGreaterThan(0);

        // 테스트 회원은 받은 비밀번호로 로그인하고, 자기 블로그 통계를 본다
        Cookie[] owner = login("test1@test.creat", PASSWORD);
        getAs("/api/blogs/" + TestDataSeeder.CAFE + "/stats?unit=day", owner).andExpect(status().isOk());
    }

    @Test
    @DisplayName("넣기를 두 번 돌려도 더 생기지 않고, 비밀번호만 새 값으로 바뀐다")
    void loadIsIdempotent() throws Exception {
        load();
        Map<String, Long> before = counts();
        TestDataSeeder.Result again = seeder.load("Other5678!", false);
        assertThat(again.created()).isFalse();
        assertThat(counts()).isEqualTo(before);
        login("test2@test.creat", "Other5678!");
    }

    @Test
    @DisplayName("실제 회원이 쓰는 닉네임·블로그 주소와 겹치면 아무것도 넣지 않는다")
    void refusesRealConflicts() throws Exception {
        member("real@example.com", "테스트채림");
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
        assertThat(id("SELECT COUNT(*) FROM members")).isEqualTo(1);
        assertThatThrownBy(() -> seeder.load("short", false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("테스트 데이터는 모두에게 보이고, 테스트 회원 이름 옆에 표시할 값이 응답에 있다")
    void visibleWithBadgeData() throws Exception {
        load();
        Cookie[] real = member("real@example.com", "실제회원");
        postAs("/api/blogs", """
                {"slug":"real-blog","name":"실제 블로그","visibility":"PUBLIC","joinPolicy":"FREE"}""", real)
                .andExpect(status().isOk());
        long realPost = ((Number) JsonPath.read(postAs("/api/blogs/real-blog/posts", """
                {"title":"실제 글","content":"본문","tags":[]}""", real).andReturn().getResponse().getContentAsString(),
                "$.id")).longValue();
        long testPost = id("SELECT id FROM posts WHERE title = '성수동 라떼 맛집 세 곳'");
        long test1 = id("SELECT id FROM members WHERE email = 'test1@test.creat'");
        long realId = id("SELECT id FROM members WHERE email = 'real@example.com'");

        // 로그인하지 않은 사람도, 실제 회원도 테스트 블로그·글을 본다
        for (Cookie[] who : List.of(new Cookie[0], real)) {
            getAs("/api/feed?tab=latest&size=30", who).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.title == '성수동 라떼 맛집 세 곳')].authorTest").value(Matchers.contains(true)))
                    .andExpect(jsonPath("$.items[?(@.title == '실제 글')].authorTest").value(Matchers.contains(false)));
            getAs("/api/blogs?sort=latest", who)
                    .andExpect(jsonPath("$.items[?(@.slug == 'test-cafe-note')].ownerTest").value(Matchers.contains(true)))
                    .andExpect(jsonPath("$.items[?(@.slug == 'real-blog')].ownerTest").value(Matchers.contains(false)));
            getAs("/api/blogs/test-cafe-note", who).andExpect(status().isOk()).andExpect(jsonPath("$.ownerTest").value(true));
            getAs("/api/posts/" + testPost, who).andExpect(status().isOk()).andExpect(jsonPath("$.authorTest").value(true));
            getAs("/api/posts/" + testPost + "/comments", who).andExpect(jsonPath("$[0].authorTest").value(true));
            getAs("/api/members/" + test1, who).andExpect(status().isOk()).andExpect(jsonPath("$.test").value(true));
            getAs("/api/search?q=라떼&type=post", who).andExpect(jsonPath("$.posts.items[0].authorTest").value(true));
            getAs("/api/blogs/test-cafe-note/posts", who).andExpect(jsonPath("$.items[0].authorTest").value(true));
        }
        getAs("/api/posts/" + realPost).andExpect(jsonPath("$.authorTest").value(false));
        getAs("/api/members/" + realId).andExpect(jsonPath("$.test").value(false));
        getAs("/api/me", real).andExpect(jsonPath("$.test").value(false));
        getAs("/api/me", login("test1@test.creat", PASSWORD)).andExpect(jsonPath("$.test").value(true));

        // 실제 회원과 테스트 회원은 서로 팔로우·댓글을 할 수 있고, 알림에도 표시할 값이 있다
        postAs("/api/members/" + test1 + "/follow", "{}", real).andExpect(status().isOk());
        Cookie[] t1 = login("test1@test.creat", PASSWORD);
        postAs("/api/posts/" + realPost + "/comments", "{\"content\":\"테스트 회원 댓글\"}", t1).andExpect(status().isOk());
        getAs("/api/notifications?tab=ALL", real).andExpect(jsonPath("$.items[0].actorTest").value(true));
    }

    @Test
    @DisplayName("관리자 회원 목록: 전체·실제 회원·테스트 회원으로 나눠 보고 수를 함께 받는다")
    void adminFilter() throws Exception {
        load();
        member("real@example.com", "실제회원");
        adminAccounts.run(null);
        Cookie temp = postJson("/api/admin/auth/login", """
                {"loginId":"rootadmin","password":"Temp1234!"}""").andReturn().getResponse().getCookie("ADMIN_TOKEN");
        Cookie admin = postAs("/api/admin/auth/password", """
                {"currentPassword":"Temp1234!","newPassword":"Admin5678!"}""", temp)
                .andReturn().getResponse().getCookie("ADMIN_TOKEN");

        getAs("/api/admin/members", admin).andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.counts.all").value(9)).andExpect(jsonPath("$.counts.real").value(1))
                .andExpect(jsonPath("$.counts.test").value(8));
        getAs("/api/admin/members?kind=test", admin).andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.items[*].test").value(Matchers.everyItem(Matchers.is(true))));
        getAs("/api/admin/members?kind=real", admin).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].nickname").value("실제회원"))
                .andExpect(jsonPath("$.items[0].test").value(false));
        getAs("/api/admin/members?kind=test&q=채림", admin).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.counts.test").value(1));
        getAs("/api/admin/members?kind=nope", admin).andExpect(status().isBadRequest());
        getAs("/api/admin/posts", admin).andExpect(jsonPath("$.items[0].authorTest").value(true));
        getAs("/api/admin/blogs", admin).andExpect(jsonPath("$.items[0].ownerTest").value(true));
    }

    @Test
    @DisplayName("지우기: 테스트 데이터와 실제 회원이 테스트 쪽에 남긴 것을 모두 지우고, 실제 회원·블로그·글은 남긴다")
    void removesOnlyTestData() throws Exception {
        load();
        Cookie[] real = member("real@example.com", "실제회원");
        Cookie[] other = member("other@example.com", "다른회원");
        postAs("/api/blogs", """
                {"slug":"real-blog","name":"실제 블로그","visibility":"PUBLIC","joinPolicy":"FREE"}""", real)
                .andExpect(status().isOk());
        long realPost = ((Number) JsonPath.read(postAs("/api/blogs/real-blog/posts", """
                {"title":"실제 글","content":"본문","tags":[]}""", real).andReturn().getResponse().getContentAsString(),
                "$.id")).longValue();
        long testPost = id("SELECT id FROM posts WHERE title = '성수동 라떼 맛집 세 곳'");
        long test1 = id("SELECT id FROM members WHERE email = 'test1@test.creat'");
        long realId = id("SELECT id FROM members WHERE email = 'real@example.com'");
        long otherId = id("SELECT id FROM members WHERE email = 'other@example.com'");

        // 실제 회원끼리의 활동 (남아야 함)
        postAs("/api/posts/" + realPost + "/like", "{}", other).andExpect(status().isOk());
        postAs("/api/posts/" + realPost + "/comments", "{\"content\":\"실제 댓글\"}", other).andExpect(status().isOk());
        postAs("/api/members/" + otherId + "/follow", "{}", real).andExpect(status().isOk());
        // 실제 회원이 테스트 쪽에 남긴 것 (지워져야 함)
        postAs("/api/posts/" + testPost + "/comments", "{\"content\":\"테스트 글에 단 댓글\"}", real).andExpect(status().isOk());
        postAs("/api/posts/" + testPost + "/like", "{}", real).andExpect(status().isOk());
        postAs("/api/members/" + test1 + "/follow", "{}", real).andExpect(status().isOk());
        postAs("/api/blogs/test-cafe-note/subscribe", "{}", real).andExpect(status().isOk());
        postAs("/api/blogs/test-cafe-note/join", "{}", other).andExpect(status().isOk());
        postAs("/api/blogs/test-cafe-note/posts", """
                {"title":"테스트 블로그에 쓴 글","content":"본문","tags":[]}""", other).andExpect(status().isOk());
        // 테스트 회원이 실제 쪽에 남긴 것 (지워져야 함)
        Cookie[] t1 = login("test1@test.creat", PASSWORD);
        postAs("/api/posts/" + realPost + "/comments", "{\"content\":\"테스트 회원 댓글\"}", t1).andExpect(status().isOk());
        postAs("/api/posts/" + realPost + "/like", "{}", t1).andExpect(status().isOk());
        postAs("/api/blogs/real-blog/join", "{}", t1).andExpect(status().isOk());
        postAs("/api/members/" + realId + "/follow", "{}", t1).andExpect(status().isOk());
        assertThat(id("SELECT like_count FROM posts WHERE id = ?", realPost)).isEqualTo(2);
        List<String> files = jdbc.queryForList("SELECT stored_name FROM images", String.class);
        // 링크 미리보기 카드: 테스트 글에만 있던 주소는 지우고, 다른 주소는 남긴다
        for (String url : List.of("https://vimeo.com/1084537", "https://example.org/real")) {
            jdbc.update("""
                    INSERT INTO link_previews (url_hash, url, status, fetched_at) VALUES (SHA2(?, 256), ?, 'FAILED', NOW())
                    """, url, url);
        }

        Map<String, Integer> removed = cleaner.remove();
        assertThat(removed.get("members")).isEqualTo(8);

        assertThat(id("SELECT COUNT(*) FROM members")).isEqualTo(2);
        assertThat(id("SELECT COUNT(*) FROM members WHERE is_test = TRUE")).isZero();
        assertThat(jdbc.queryForList("SELECT slug FROM blogs", String.class)).containsExactly("real-blog");
        assertThat(jdbc.queryForList("SELECT title FROM posts", String.class)).containsExactly("실제 글");
        assertThat(jdbc.queryForList("SELECT content FROM comments", String.class)).containsExactly("실제 댓글");
        assertThat(id("SELECT like_count FROM posts WHERE id = ?", realPost)).isEqualTo(1);
        assertThat(id("SELECT comment_count FROM posts WHERE id = ?", realPost)).isEqualTo(1);
        assertThat(id("SELECT COUNT(*) FROM post_likes")).isEqualTo(1);
        assertThat(id("SELECT COUNT(*) FROM follows")).isEqualTo(1);
        assertThat(id("SELECT COUNT(*) FROM blog_memberships")).isEqualTo(1);
        for (String t : List.of("subscriptions", "join_requests", "ownership_transfers", "member_sanctions", "reports",
                "blog_visits", "images", "blocks", "notification_settings", "categories")) {
            assertThat(id("SELECT COUNT(*) FROM " + t)).as(t).isZero();
        }
        assertThat(id("SELECT COUNT(*) FROM notifications WHERE actor_id NOT IN (?, ?) OR recipient_id NOT IN (?, ?)",
                realId, otherId, realId, otherId)).isZero();
        files.forEach(f -> assertThat(Files.exists(Path.of(uploads.dir(), f))).as(f).isFalse());
        assertThat(jdbc.queryForList("SELECT url FROM link_previews", String.class)).containsExactly("https://example.org/real");

        // 실제 회원은 그대로 쓴다. 지운 뒤 다시 넣을 수도 있다
        getAs("/api/blogs/real-blog", real).andExpect(status().isOk());
        assertThat(cleaner.remove()).isEmpty();
        assertThat(load().created()).isTrue();
    }
}
