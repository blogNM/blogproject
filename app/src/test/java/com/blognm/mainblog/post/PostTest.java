package com.blognm.mainblog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 004 User Story 1·2 - 글 쓰고 읽기, 수정·삭제 권한 */
class PostTest extends IntegrationTest {

    @Autowired
    private PostCleanupJob cleanupJob;

    private Cookie[] owner;
    private Cookie[] writer;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        writer = member("writer@example.com", "작성자");
        postAs("/api/blogs", """
                {"slug":"food","name":"맛집","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
        postAs("/api/blogs/food/join", null, writer).andExpect(status().isOk());
    }

    private long write(Cookie[] who, String slug, String title, String content) throws Exception {
        String body = postAs("/api/blogs/" + slug + "/posts", """
                {"title":"%s","content":"%s","tags":[]}""".formatted(title, content), who)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    @DisplayName("멤버가 쓴 글은 비회원도 읽는다. 멤버가 아니면 쓸 수 없다")
    void writeAndRead() throws Exception {
        long id = write(writer, "food", "첫 글", "## 안녕\\n\\n**반가워요**");
        getAs("/api/posts/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("첫 글"))
                .andExpect(jsonPath("$.authorName").value("작성자"))
                .andExpect(jsonPath("$.contentHtml").value("<h2>안녕</h2>\n<p><strong>반가워요</strong></p>\n"))
                .andExpect(jsonPath("$.contentMarkdown").doesNotExist())
                .andExpect(jsonPath("$.canEdit").value(false));
        getAs("/api/posts/" + id, writer).andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.contentMarkdown").value("## 안녕\n\n**반가워요**"));

        Cookie[] outsider = member("out@example.com", "바깥");
        postAs("/api/blogs/food/posts", """
                {"title":"몰래","content":"본문"}""", outsider)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_MEMBER"));
        postAs("/api/blogs/food/posts", """
                {"title":"몰래","content":"본문"}""").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("본문의 스크립트·이벤트 속성·javascript 링크·iframe·style은 서버가 걸러낸다 (US1-2)")
    void xssIsFiltered() throws Exception {
        long id = write(writer, "food", "XSS",
                "<script>alert(1)</script>\\n\\n<img src=x onerror=alert(2)>\\n\\n[링크](javascript:alert(3))\\n\\n"
                        + "<iframe src=//evil.example></iframe>\\n\\n<p style=\\\"color:red\\\" onclick=\\\"x()\\\">글</p>\\n\\n"
                        + "![밖 사진](https://evil.example/a.png)\\n\\n[좋은 링크](https://example.com) 를 남긴다");
        String html = JsonPath.read(getAs("/api/posts/" + id).andReturn().getResponse().getContentAsString(),
                "$.contentHtml");
        assertThat(html).doesNotContain("<script", "onerror", "javascript:", "<iframe", "style=", "onclick",
                "evil.example");
        assertThat(html).contains("<a href=\"https://example.com\" rel=\"nofollow noopener noreferrer\">좋은 링크</a>");
    }

    @Test
    @DisplayName("글자 색은 정해 둔 색 class만 남고, 다른 class·style은 지워져 글자만 남는다. 색 표시는 글자 수에 넣지 않는다")
    void textColor() throws Exception {
        long id = write(writer, "food", "색",
                "<span class=\\\"tc-red\\\">빨강</span> <span class=\\\"evil\\\">다른</span> "
                        + "<span style=\\\"color:red\\\">스타일</span>");
        String html = JsonPath.read(getAs("/api/posts/" + id).andReturn().getResponse().getContentAsString(),
                "$.contentHtml");
        assertThat(html).contains("<span class=\"tc-red\">빨강</span>", "다른", "스타일")
                .doesNotContain("evil", "style=");
        assertThat(PostRules.contentLength("<span class=\"tc-blue\">가나</span>")).isEqualTo(2);
    }

    @Test
    @DisplayName("보이는 그대로 편집기가 겹쳐 감싼 크기·형광펜·색 span은 그대로 남고, 표시는 글자 수에 넣지 않는다 (D-106)")
    void nestedFormatSpans() throws Exception {
        String nested = "<span class=\\\"ts-large\\\"><span class=\\\"hl-yellow\\\"><span class=\\\"tc-red\\\">크고 빨강</span></span></span>"
                + " **<span class=\\\"tc-blue\\\">굵은 파랑</span>**";
        long id = write(writer, "food", "겹친 서식", nested);
        String html = JsonPath.read(getAs("/api/posts/" + id).andReturn().getResponse().getContentAsString(),
                "$.contentHtml");
        assertThat(html).contains(
                "<span class=\"ts-large\"><span class=\"hl-yellow\"><span class=\"tc-red\">크고 빨강</span></span></span>",
                "<strong><span class=\"tc-blue\">굵은 파랑</span></strong>");
        assertThat(PostRules.contentLength(
                "<span class=\"ts-xlarge\"><span class=\"hl-pink\">가나</span></span><span class=\"ts-small\">다</span>"))
                .isEqualTo(3);
        // 줄 하나 바꿈은 글 화면에서도 줄바꿈, 빈 줄은 문단 나눔 (D-106)
        assertThat(new MarkdownRenderer().toSafeHtml("첫 줄\n둘째 줄\n\n새 문단"))
                .isEqualTo("<p>첫 줄<br>\n둘째 줄</p>\n<p>새 문단</p>\n");
        // 여러 class를 한 span에 넣으면 정해 둔 모양이 아니므로 글자만 남는다 (편집기는 하나씩 감싸서 저장한다)
        assertThat(new MarkdownRenderer().toSafeHtml("<span class=\"tc-red ts-large\">둘</span>")).isEqualTo("<p>둘</p>\n");
    }

    @Test
    @DisplayName("체크박스 목록은 누를 수 없는 checkbox로 보이고, 다른 input은 지워지고, 표시는 글자 수에 넣지 않는다 (D-110)")
    void taskList() throws Exception {
        MarkdownRenderer renderer = new MarkdownRenderer();
        String md = "- 글머리\n- [ ] 할 일\n- [x] 한 일\n1. 번호";
        assertThat(renderer.toSafeHtml(md)).isEqualTo("<ul>\n<li>글머리</li>\n"
                + "<li class=\"task-list-item\"><input type=\"checkbox\" disabled> 할 일</li>\n"
                + "<li class=\"task-list-item\"><input type=\"checkbox\" checked disabled> 한 일</li>\n</ul>\n"
                + "<ol>\n<li>번호</li>\n</ol>\n");
        // 직접 쓴 input은 checkbox가 아니거나 목록 줄 맨 앞이 아니면 지우고, 다른 속성은 남기지 않는다
        String injected = renderer.toSafeHtml("<input type=\"text\" value=\"x\"> <input type=\"password\">\n\n"
                + "<ul><li><input type=\"checkbox\" name=\"a\" onclick=\"alert(1)\" id=\"x\">직접</li>"
                + "<li>뒤 <input type=\"checkbox\"></li></ul>\n\n<input type=\"checkbox\" checked>");
        assertThat(injected).doesNotContain("text", "password", "onclick", "name=", "id=", "value=")
                .contains("<li class=\"task-list-item\"><input type=\"checkbox\" disabled>직접</li><li>뒤 </li>");
        assertThat(injected.split("<input", -1)).hasSize(2);
        // 크기 class는 크기만, 굵게는 따로 (D-110)
        assertThat(renderer.toSafeHtml("<span class=\"ts-xlarge\">큰 글자</span>"))
                .isEqualTo("<p><span class=\"ts-xlarge\">큰 글자</span></p>\n");
        // "- [ ] "는 "- "만 센다 (글머리 목록과 같게)
        assertThat(PostRules.contentLength("- [ ] 가나\n- [x] 다\n* [X]\n- 라")).isEqualTo(
                PostRules.contentLength("- 가나\n- 다\n* \n- 라"));
        assertThat(PostRules.contentLength("[ ] 가")).isEqualTo(5);

        // 글로 저장했다가 다시 불러오면 마크다운이 그대로, 글 화면에는 checkbox
        String stored = "- [ ] 우유 사기\\n- [x] <span class=\\\"ts-large\\\">빵 사기</span>\\n- 글머리 하나";
        long id = write(writer, "food", "할 일", stored);
        getAs("/api/posts/" + id, writer)
                .andExpect(jsonPath("$.contentMarkdown").value("- [ ] 우유 사기\n- [x] <span class=\"ts-large\">빵 사기</span>\n- 글머리 하나"))
                .andExpect(jsonPath("$.contentHtml").value("<ul>\n"
                        + "<li class=\"task-list-item\"><input type=\"checkbox\" disabled> 우유 사기</li>\n"
                        + "<li class=\"task-list-item\"><input type=\"checkbox\" checked disabled> <span class=\"ts-large\">빵 사기</span></li>\n"
                        + "<li>글머리 하나</li>\n</ul>\n"));
    }

    @Test
    @DisplayName("사진 크기 표시는 #w=10~90(10 단위)만 남기고, 다른 # 뒤 글자는 지운다 (D-114)")
    void imageWidthMark() {
        MarkdownRenderer renderer = new MarkdownRenderer();
        String id = "/uploads/3f2b9c4e-0d1a-4b8c-9e7f-1a2b3c4d5e6f";
        assertThat(renderer.toSafeHtml("![카페](" + id + "#w=60)\n이어 쓴 글"))
                .isEqualTo("<p><img src=\"" + id + "#w=60\" alt=\"카페\"><br>\n이어 쓴 글</p>\n");
        assertThat(renderer.toSafeHtml("![a](" + id + ")")).contains("src=\"" + id + "\"");
        // 정해 두지 않은 값·다른 표시는 지운다
        for (String bad : new String[] {"#w=55", "#w=100", "#w=5", "#w=60x", "#x", "#w=60\"onerror=", "#"}) {
            assertThat(MarkdownRenderer.keepOnlyWidthMark(id + bad)).as(bad).isEqualTo(id);
        }
        // 사진 문법은 크기 표시가 붙어도 글자 수에 넣지 않는다
        assertThat(PostRules.contentLength("![a](" + id + "#w=30)가")).isEqualTo(1);
    }

    @Test
    @DisplayName("제목 1~30자, 본문 1000자(사진 문법은 빼고 셈), 태그 10개. 태그는 #·공백 정리 후 소문자 하나로")
    void inputRules() throws Exception {
        postAs("/api/blogs/food/posts", """
                {"title":"%s","content":"본문"}""".formatted("가".repeat(31)), writer)
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
        postAs("/api/blogs/food/posts", """
                {"title":"제목","content":"%s"}""".formatted("가".repeat(1001)), writer)
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));
        postAs("/api/blogs/food/posts", """
                {"title":"제목","content":"%s![사진](/uploads/00000000-0000-0000-0000-000000000000)"}"""
                .formatted("가".repeat(1000)), writer).andExpect(status().isOk());
        postAs("/api/blogs/food/posts", """
                {"title":"제목","content":"본문","tags":["a","b","c","d","e","f","g","h","i","j","k"]}""", writer)
                .andExpect(jsonPath("$.code").value("TOO_MANY_TAGS"));
        postAs("/api/blogs/food/posts", """
                {"title":"제목","content":"본문","tags":["bad tag!"]}""", writer)
                .andExpect(jsonPath("$.code").value("INVALID_TAG"));

        String body = postAs("/api/blogs/food/posts", """
                {"title":"태그","content":"본문","tags":["#맛집 ","서울 맛집","Java","java"]}""", writer)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        getAs("/api/posts/" + id).andExpect(jsonPath("$.tags.length()").value(3))
                .andExpect(jsonPath("$.tags[0]").value("java"))
                .andExpect(jsonPath("$.tags[1]").value("맛집"))
                .andExpect(jsonPath("$.tags[2]").value("서울_맛집"));
    }

    @Test
    @DisplayName("블로그장은 남의 글을 고칠 수 없고 지울 수는 있다. 지우면 작성자에게 알림이 간다 (US2-1, D-58)")
    void ownerDeletesButCannotEdit() throws Exception {
        long id = write(writer, "food", "멤버 글", "본문");
        sendAs(HttpMethod.PUT, "/api/posts/" + id, """
                {"title":"바꿈","content":"바꿈"}""", owner)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_AUTHOR"));
        getAs("/api/posts/" + id, owner).andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.canDelete").value(true));

        sendAs(HttpMethod.DELETE, "/api/posts/" + id, null, owner).andExpect(status().isOk());
        getAs("/api/posts/" + id).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT CONCAT(status, '/', deleted_by) FROM posts WHERE id = ?", String.class, id))
                .isEqualTo("DELETED/OWNER");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n JOIN members m ON m.id = n.recipient_id
                WHERE m.email = 'writer@example.com' AND n.type = 'POST_DELETED' AND n.target_id = ?""",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("작성자는 고치고 지운다. 다른 멤버, 다른 블로그의 블로그장은 못 지운다 (US2-2)")
    void authorAndOthers() throws Exception {
        long id = write(writer, "food", "내 글", "본문");
        sendAs(HttpMethod.PUT, "/api/posts/" + id, """
                {"title":"고친 글","content":"고친 본문","tags":["새태그"]}""", writer).andExpect(status().isOk());
        getAs("/api/posts/" + id).andExpect(jsonPath("$.title").value("고친 글"))
                .andExpect(jsonPath("$.tags[0]").value("새태그"));

        Cookie[] another = member("another@example.com", "다른멤버");
        postAs("/api/blogs/food/join", null, another).andExpect(status().isOk());
        sendAs(HttpMethod.DELETE, "/api/posts/" + id, null, another).andExpect(status().isForbidden());

        Cookie[] otherOwner = member("other-owner@example.com", "다른블로그장");
        postAs("/api/blogs", """
                {"slug":"other","name":"다른 블로그","visibility":"PUBLIC","joinPolicy":"FREE"}""", otherOwner)
                .andExpect(status().isOk());
        sendAs(HttpMethod.DELETE, "/api/posts/" + id, null, otherOwner).andExpect(status().isForbidden());

        sendAs(HttpMethod.DELETE, "/api/posts/" + id, null, writer).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM posts WHERE id = ?", String.class, id)).isEqualTo("AUTHOR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'POST_DELETED'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("글 관리 권한이 있는 부블로그장만 남의 글을 지우고 공지를 쓴다 (D-71)")
    void subOwnerManagePosts() throws Exception {
        long id = write(owner, "food", "블로그장 글", "본문");
        jdbc.update("UPDATE blog_memberships SET role = 'SUB_OWNER' WHERE member_id = (SELECT id FROM members WHERE email = 'writer@example.com')");
        sendAs(HttpMethod.DELETE, "/api/posts/" + id, null, writer).andExpect(status().isForbidden());
        postAs("/api/blogs/food/posts", """
                {"title":"공지","content":"본문","notice":true}""", writer)
                .andExpect(status().isForbidden());

        jdbc.update("UPDATE blog_memberships SET perm_manage_posts = TRUE WHERE role = 'SUB_OWNER'");
        postAs("/api/blogs/food/posts", """
                {"title":"공지","content":"본문","notice":true}""", writer).andExpect(status().isOk());
        sendAs(HttpMethod.DELETE, "/api/posts/" + id, null, writer).andExpect(status().isOk());
    }

    @Test
    @DisplayName("블로그를 나간 멤버의 글은 \"탈퇴한 계정\", 사이트를 탈퇴하면 \"탈퇴한 회원\"으로 보인다")
    void leftAuthor() throws Exception {
        long id = write(writer, "food", "남는 글", "본문");
        postAs("/api/blogs/food/leave", null, writer).andExpect(status().isOk());
        getAs("/api/posts/" + id).andExpect(jsonPath("$.authorName").value("탈퇴한 계정"));
        // 다시 들어와도 예전 글은 고칠 수 없다
        postAs("/api/blogs/food/join", null, writer).andExpect(status().isOk());
        sendAs(HttpMethod.PUT, "/api/posts/" + id, """
                {"title":"바꿈","content":"바꿈"}""", writer).andExpect(status().isForbidden());

        long ownerPost = write(owner, "food", "블로그장 글", "본문");
        jdbc.update("UPDATE members SET status = 'WITHDRAWN' WHERE email = 'owner@example.com'");
        getAs("/api/posts/" + ownerPost).andExpect(jsonPath("$.authorName").value("탈퇴한 회원"));
    }

    @Test
    @DisplayName("비공개 블로그의 글은 멤버만, 일부 공개는 공유 링크 값이 맞아야 읽는다. 정지된 멤버는 못 쓴다")
    void visibility() throws Exception {
        postAs("/api/blogs", """
                {"slug":"secret","name":"비밀","visibility":"PRIVATE","joinPolicy":"APPROVAL"}""", owner)
                .andExpect(status().isOk());
        long secret = write(owner, "secret", "비밀 글", "본문");
        getAs("/api/posts/" + secret).andExpect(status().isNotFound());
        getAs("/api/posts/" + secret, writer).andExpect(status().isNotFound());
        getAs("/api/posts/" + secret, owner).andExpect(status().isOk());

        postAs("/api/blogs", """
                {"slug":"link","name":"링크","visibility":"LINK_ONLY","joinPolicy":"APPROVAL"}""", owner)
                .andExpect(status().isOk());
        long linkPost = write(owner, "link", "링크 글", "본문");
        String key = jdbc.queryForObject("SELECT share_token FROM blogs WHERE slug = 'link'", String.class);
        getAs("/api/posts/" + linkPost).andExpect(status().isNotFound());
        getAs("/api/posts/" + linkPost + "?key=" + key).andExpect(status().isOk());
        getAs("/api/blogs/link/posts?key=" + key).andExpect(jsonPath("$.items[0].title").value("링크 글"));
        getAs("/api/blogs/link/posts").andExpect(status().isNotFound());

        jdbc.update("UPDATE blog_memberships SET suspended_until = ? WHERE member_id = (SELECT id FROM members WHERE email = 'writer@example.com')",
                java.time.LocalDateTime.now(clock).plusDays(3));
        postAs("/api/blogs/food/posts", """
                {"title":"정지 중","content":"본문"}""", writer)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUSPENDED"));
    }

    @Test
    @DisplayName("폐쇄 예정 블로그에도 글을 쓴다 (D-67). 지운 글은 30일 뒤 완전히 지운다 (US2-4)")
    void closingAndPurge() throws Exception {
        jdbc.update("UPDATE blogs SET status = 'CLOSING' WHERE slug = 'food'");
        long id = write(writer, "food", "폐쇄 전 글", "본문");
        sendAs(HttpMethod.DELETE, "/api/posts/" + id, null, writer).andExpect(status().isOk());

        clock.advance(java.time.Duration.ofDays(29));
        cleanupJob.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE id = ?", Integer.class, id)).isEqualTo(1);
        clock.advance(java.time.Duration.ofDays(2));
        cleanupJob.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE id = ?", Integer.class, id)).isZero();
    }
}
