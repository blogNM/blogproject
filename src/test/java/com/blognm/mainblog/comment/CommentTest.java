package com.blognm.mainblog.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 004 User Story 5 - 댓글·대댓글 */
class CommentTest extends IntegrationTest {

    private Cookie[] owner;
    private Cookie[] reader;
    private Cookie[] other;
    private long postId;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        reader = member("reader@example.com", "독자");
        other = member("other@example.com", "다른독자");
        postAs("/api/blogs", """
                {"slug":"food","name":"맛집","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
        postId = id(postAs("/api/blogs/food/posts", """
                {"title":"첫 글","content":"본문","tags":[]}""", owner));
    }

    private static long id(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.id")).longValue();
    }

    private long comment(Cookie[] who, String content, Long parentId) throws Exception {
        return id(postAs("/api/posts/" + postId + "/comments", """
                {"content":"%s","parentId":%s}""".formatted(content, parentId), who));
    }

    @Test
    @DisplayName("답글에 다시 답하면 같은 줄기(1단계)에 붙고 @닉네임이 붙는다. 글쓴이와 답글 대상에게 알림 (D-78)")
    void replyDepthStaysOne() throws Exception {
        long root = comment(reader, "맛있어 보여요", null);
        long reply = comment(owner, "감사합니다", root);
        comment(other, "저도 가 볼게요", reply);

        getAs("/api/posts/" + postId + "/comments")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].authorName").value("독자"))
                .andExpect(jsonPath("$[0].replies.length()").value(2))
                .andExpect(jsonPath("$[0].replies[0].replyTo").value("독자"))
                .andExpect(jsonPath("$[0].replies[1].replyTo").value("블로그장"));
        getAs("/api/posts/" + postId).andExpect(jsonPath("$.counts.comments").value(3));

        Integer toOwner = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n JOIN members m ON m.id = n.recipient_id
                WHERE m.nickname = '블로그장' AND n.tab = 'COMMENT'""", Integer.class);
        Integer toReader = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n JOIN members m ON m.id = n.recipient_id
                WHERE m.nickname = '독자' AND n.type = 'REPLY'""", Integer.class);
        assertThat(toOwner).isEqualTo(2);
        assertThat(toReader).isEqualTo(1);
    }

    @Test
    @DisplayName("답글이 있는 댓글을 지우면 \"삭제된 댓글입니다\"로 남고, 답글이 없으면 사라진다 (US5-2)")
    void deleteKeepsReplies() throws Exception {
        long root = comment(reader, "첫 댓글", null);
        comment(other, "답글", root);
        long lonely = comment(other, "혼자", null);

        sendAs(HttpMethod.DELETE, "/api/comments/" + root, null, reader).andExpect(status().isOk());
        sendAs(HttpMethod.DELETE, "/api/comments/" + lonely, null, other).andExpect(status().isOk());

        getAs("/api/posts/" + postId + "/comments")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deleted").value(true))
                .andExpect(jsonPath("$[0].content").value("삭제된 댓글입니다."))
                .andExpect(jsonPath("$[0].authorName").doesNotExist())
                .andExpect(jsonPath("$[0].replies[0].content").value("답글"));
        getAs("/api/posts/" + postId).andExpect(jsonPath("$.counts.comments").value(1));
    }

    @Test
    @DisplayName("고친 댓글은 \"수정됨\". 남의 댓글은 못 고치고, 블로그장은 남의 댓글을 지울 수 있다")
    void editAndDeletePermissions() throws Exception {
        long c = comment(reader, "처음", null);
        sendAs(HttpMethod.PUT, "/api/comments/" + c, """
                {"content":"고침"}""", other).andExpect(status().isForbidden());
        sendAs(HttpMethod.PUT, "/api/comments/" + c, """
                {"content":"고침"}""", reader).andExpect(status().isOk());
        getAs("/api/posts/" + postId + "/comments", reader)
                .andExpect(jsonPath("$[0].content").value("고침"))
                .andExpect(jsonPath("$[0].edited").value(true))
                .andExpect(jsonPath("$[0].mine").value(true));

        sendAs(HttpMethod.DELETE, "/api/comments/" + c, null, other).andExpect(status().isForbidden());
        sendAs(HttpMethod.DELETE, "/api/comments/" + c, null, owner).andExpect(status().isOk());
    }

    @Test
    @DisplayName("댓글은 1~500자, 비회원은 못 단다 (US5-5)")
    void lengthAndLogin() throws Exception {
        postAs("/api/posts/" + postId + "/comments", """
                {"content":"%s"}""".formatted("가".repeat(501)), reader)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COMMENT"));
        postAs("/api/posts/" + postId + "/comments", """
                {"content":"   "}""", reader).andExpect(status().isBadRequest());
        postAs("/api/posts/" + postId + "/comments", """
                {"content":"%s"}""".formatted("가".repeat(500)), reader).andExpect(status().isOk());
        postAs("/api/posts/" + postId + "/comments", """
                {"content":"비회원"}""").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비공개 블로그 글에는 멤버가 아니면 댓글을 읽지도 달지도 못한다")
    void privateBlog() throws Exception {
        postAs("/api/blogs", """
                {"slug":"secret","name":"비밀","visibility":"PRIVATE","joinPolicy":"APPROVAL"}""", owner)
                .andExpect(status().isOk());
        long secret = id(postAs("/api/blogs/secret/posts", """
                {"title":"비밀 글","content":"본문","tags":[]}""", owner));
        getAs("/api/posts/" + secret + "/comments", reader).andExpect(status().isNotFound());
        postAs("/api/posts/" + secret + "/comments", """
                {"content":"몰래"}""", reader).andExpect(status().isNotFound());
    }
}
