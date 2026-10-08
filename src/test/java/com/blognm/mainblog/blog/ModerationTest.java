package com.blognm.mainblog.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 블로그장·부블로그장의 멤버 관리, 제재, 블랙리스트, 신고 처리, 위임, 폐쇄 (002 US4·US5, 6단계). */
class ModerationTest extends IntegrationTest {

    @Autowired
    BlogLifecycleService lifecycle;

    private Cookie[] owner;
    private Cookie[] sub;
    private Cookie[] member;
    private long ownerId;
    private long subId;
    private long memberId;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        sub = member("sub@example.com", "부장");
        member = member("member@example.com", "멤버");
        ownerId = id("owner@example.com");
        subId = id("sub@example.com");
        memberId = id("member@example.com");
        postAs("/api/blogs", """
                {"slug":"club","name":"모임","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner).andExpect(status().isOk());
        postAs("/api/blogs/club/join", null, sub).andExpect(status().isOk());
        postAs("/api/blogs/club/join", null, member).andExpect(status().isOk());
    }

    private long id(String email) {
        return jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, email);
    }

    private void makeSub(boolean manageMembers) throws Exception {
        sendAs(HttpMethod.PUT, "/api/blogs/club/members/" + subId + "/role", """
                {"role":"SUB_OWNER","permissions":{"editInfo":false,"manageMembers":%s,"managePosts":false}}"""
                .formatted(manageMembers), owner).andExpect(status().isOk());
    }

    private int notifications(long recipient, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Integer.class, recipient, type);
    }

    @Test
    @DisplayName("멤버 관리 권한을 받은 부블로그장만 제재하고, 블로그장은 그 기록을 본다 (D-71)")
    void subOwnerSanctions() throws Exception {
        makeSub(false);
        postAs("/api/blogs/club/members/" + memberId + "/sanctions", """
                {"action":"WARNING","reason":"도배"}""", sub).andExpect(status().isForbidden());
        makeSub(true);
        postAs("/api/blogs/club/members/" + memberId + "/sanctions", """
                {"action":"WARNING","reason":"도배"}""", sub).andExpect(status().isOk());
        // 부블로그장은 블로그장을 제재할 수 없다
        postAs("/api/blogs/club/members/" + ownerId + "/sanctions", """
                {"action":"WARNING","reason":"x"}""", sub).andExpect(status().is4xxClientError());
        getAs("/api/blogs/club/sanctions", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("WARNING")).andExpect(jsonPath("$[0].reason").value("도배"));
        assertThat(notifications(memberId, "MEMBER_WARNED")).isEqualTo(1);
        // 일반 멤버는 멤버 목록도 못 본다
        getAs("/api/blogs/club/members", member).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("정지 중에는 글을 못 쓰고, 1년에 3번 정지되면 강퇴 권유가 붙는다 (D-46)")
    void suspendThreeTimes() throws Exception {
        for (int i = 1; i <= 3; i++) {
            postAs("/api/blogs/club/members/" + memberId + "/sanctions", """
                    {"action":"SUSPEND","duration":"D3","reason":"욕설"}""", owner)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.suspensions").value(i))
                    .andExpect(jsonPath("$.kickSuggested").value(i == 3));
            postAs("/api/blogs/club/posts", """
                    {"title":"글","content":"본문","tags":[]}""", member)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUSPENDED"));
            clock.advance(Duration.ofDays(4));
            owner = login("owner@example.com", "Abcd1234!");
            member = login("member@example.com", "Abcd1234!");
        }
        postAs("/api/blogs/club/posts", """
                {"title":"글","content":"본문","tags":[]}""", member).andExpect(status().isOk());
    }

    @Test
    @DisplayName("강퇴하면 블랙리스트에 올라 다시 못 들어오고, 이의 신청을 받아 풀어 주면 다시 들어온다 (BLG-13)")
    void kickBlacklistAppeal() throws Exception {
        postAs("/api/blogs/club/members/" + memberId + "/sanctions", """
                {"action":"KICK","reason":"광고"}""", owner).andExpect(status().isOk());
        postAs("/api/blogs/club/join", null, member)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("JOIN_BLACKLISTED"));
        getAs("/api/blogs/club/blacklist", owner).andExpect(jsonPath("$.length()").value(1));
        postAs("/api/blogs/club/blacklist-appeals", """
                {"message":"다시는 안 그러겠습니다"}""", member).andExpect(status().isOk());
        String appeals = getAs("/api/blogs/club/blacklist-appeals", owner).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long appealId = ((Number) JsonPath.read(appeals, "$[0].id")).longValue();
        postAs("/api/blogs/club/blacklist-appeals/" + appealId + "/lift", null, owner).andExpect(status().isOk());
        assertThat(notifications(memberId, "BLACKLIST_LIFTED")).isEqualTo(1);
        postAs("/api/blogs/club/join", null, member).andExpect(jsonPath("$.joined").value(true));
    }

    @Test
    @DisplayName("멤버 글 신고는 블로그장이 처리하고, 결과가 신고자에게 간다 (BLG-13, D-85)")
    void ownerProcessesReport() throws Exception {
        String r = postAs("/api/blogs/club/posts", """
                {"title":"광고글","content":"본문","tags":[]}""", member).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long postId = ((Number) JsonPath.read(r, "$.id")).longValue();
        postAs("/api/reports", """
                {"targetType":"POST","targetId":%d,"reason":"SPAM"}""".formatted(postId), sub)
                .andExpect(jsonPath("$.handler").value("BLOG_OWNER"));
        String list = getAs("/api/blogs/club/reports?status=PENDING", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andReturn().getResponse().getContentAsString();
        long reportId = ((Number) JsonPath.read(list, "$.items[0].id")).longValue();
        postAs("/api/blogs/club/reports/" + reportId, """
                {"result":"WARNING","reason":"광고"}""", owner).andExpect(status().isOk());
        getAs("/api/blogs/club/reports?status=PENDING", owner).andExpect(jsonPath("$.total").value(0));
        assertThat(notifications(subId, "REPORT_RESULT")).isEqualTo(1);
        assertThat(notifications(memberId, "MEMBER_WARNED")).isEqualTo(1);
    }

    @Test
    @DisplayName("위임을 받아들이면 블로그장이 바뀌고 전 블로그장은 멤버로 남는다 (BLG-09)")
    void transfer() throws Exception {
        postAs("/api/blogs/club/transfer", "{\"memberId\":%d}".formatted(memberId), owner).andExpect(status().isOk());
        assertThat(notifications(memberId, "TRANSFER_REQUESTED")).isEqualTo(1);
        postAs("/api/blogs/club/transfer/accept", null, member).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT owner_id FROM blogs WHERE slug = 'club'", Long.class)).isEqualTo(memberId);
        assertThat(jdbc.queryForObject("SELECT role FROM blog_memberships WHERE member_id = ? AND status = 'ACTIVE'",
                String.class, ownerId)).isEqualTo("MEMBER");
        postAs("/api/blogs/club/close", null, owner).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("폐쇄는 7일 뒤 04:00에 닫히고 그 전에는 철회할 수 있다 (BLG-09, D-86)")
    void closeAndCancel() throws Exception {
        postAs("/api/blogs/club/close", null, owner).andExpect(status().isOk());
        assertThat(notifications(memberId, "BLOG_CLOSING")).isEqualTo(1);
        assertThat(notifications(ownerId, "BLOG_CLOSING")).isZero();
        sendAs(HttpMethod.DELETE, "/api/blogs/club/close", null, owner).andExpect(status().isOk());
        assertThat(notifications(memberId, "BLOG_CLOSE_CANCELLED")).isEqualTo(1);

        postAs("/api/blogs/club/close", null, owner).andExpect(status().isOk());
        clock.advance(Duration.ofDays(8));
        assertThat(lifecycle.closeDue()).isEqualTo(1);
        getAs("/api/blogs/club").andExpect(status().isNotFound());
        clock.advance(Duration.ofDays(31));
        assertThat(lifecycle.purgeClosed()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE slug = 'club'", Integer.class)).isZero();
    }
}
