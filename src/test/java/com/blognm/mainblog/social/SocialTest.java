package com.blognm.mainblog.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.notification.NotificationCleanupJob;
import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 003 소셜: 팔로우·구독(US1~2), 프로필·내 활동(SOC-03, SOC-07), 차단(SOC-05), 신고(US5), 알림(SOC-04) */
class SocialTest extends IntegrationTest {

    @Autowired
    NotificationCleanupJob cleanupJob;

    private Cookie[] owner;
    private Cookie[] reader;
    private Cookie[] other;
    private long ownerId;
    private long readerId;
    private long otherId;
    private long postId;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        reader = member("reader@example.com", "독자");
        other = member("other@example.com", "다른독자");
        ownerId = memberId("owner@example.com");
        readerId = memberId("reader@example.com");
        otherId = memberId("other@example.com");
        postAs("/api/blogs", """
                {"slug":"food","name":"맛집","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
        postId = id(postAs("/api/blogs/food/posts", """
                {"title":"첫 글","content":"본문","tags":[]}""", owner));
    }

    private long memberId(String email) {
        return jdbc.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, email);
    }

    private static long id(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.id")).longValue();
    }

    private int notifications(long recipient, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Integer.class, recipient, type);
    }

    @Test
    @DisplayName("팔로우는 한 번 더 누르면 취소되고, 껐다 켜도 알림은 하루 한 번만 간다 (SOC-01)")
    void followToggles() throws Exception {
        postAs("/api/members/" + ownerId + "/follow", null, reader)
                .andExpect(jsonPath("$.on").value(true)).andExpect(jsonPath("$.count").value(1));
        postAs("/api/members/" + ownerId + "/follow", null, reader)
                .andExpect(jsonPath("$.on").value(false)).andExpect(jsonPath("$.count").value(0));
        postAs("/api/members/" + ownerId + "/follow", null, reader).andExpect(jsonPath("$.on").value(true));
        assertThat(notifications(ownerId, "NEW_FOLLOWER")).isEqualTo(1);

        postAs("/api/members/" + readerId + "/follow", null, reader).andExpect(status().isBadRequest());
        getAs("/api/members/" + readerId + "/following").andExpect(jsonPath("$.items[0].nickname").value("블로그장"));
    }

    @Test
    @DisplayName("구독하면 블로그장에게 알림이 가고 블로그 정보에 구독 수가 보인다 (US2-3)")
    void subscribe() throws Exception {
        postAs("/api/blogs/food/subscribe", null, reader).andExpect(jsonPath("$.on").value(true));
        assertThat(notifications(ownerId, "BLOG_SUBSCRIBED")).isEqualTo(1);
        getAs("/api/blogs/food", reader)
                .andExpect(jsonPath("$.subscribed").value(true))
                .andExpect(jsonPath("$.subscribers").value(1))
                .andExpect(jsonPath("$.ownerId").value(ownerId));
        getAs("/api/members/" + readerId + "/subscriptions").andExpect(jsonPath("$.items[0].slug").value("food"));
    }

    @Test
    @DisplayName("다른 사람의 프로필에는 이메일·이름·전화번호가 없다 (SC-003)")
    void profileHasNoPrivateInfo() throws Exception {
        getAs("/api/members/" + ownerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("블로그장"))
                .andExpect(jsonPath("$.ownedBlogs[0].slug").value("food"))
                .andExpect(jsonPath("$.mySettings").doesNotExist())
                .andExpect(content().string(not(containsString("owner@example.com"))));
        getAs("/api/members/" + ownerId + "/followers")
                .andExpect(content().string(not(containsString("@example.com"))));
    }

    @Test
    @DisplayName("공개를 끈 목록은 남에게 막히고 본인은 본다. 기본은 공개 (SOC-07, D-91)")
    void activityVisibility() throws Exception {
        postAs("/api/posts/" + postId + "/like", null, reader).andExpect(status().isOk());
        getAs("/api/members/" + readerId + "/likes").andExpect(jsonPath("$.items[0].title").value("첫 글"));

        sendAs(HttpMethod.PUT, "/api/me/activity-visibility", """
                {"posts":true,"likes":false,"following":true,"subscriptions":true}""", reader)
                .andExpect(status().isOk());
        getAs("/api/members/" + readerId + "/likes").andExpect(status().isForbidden());
        getAs("/api/members/" + readerId + "/likes", other).andExpect(status().isForbidden());
        getAs("/api/members/" + readerId + "/likes", reader).andExpect(jsonPath("$.items.length()").value(1));
        getAs("/api/members/" + readerId, reader)
                .andExpect(jsonPath("$.me").value(true))
                .andExpect(jsonPath("$.mySettings.likes").value(false));
        getAs("/api/members/" + readerId).andExpect(jsonPath("$.visible.likes").value(false));
    }

    @Test
    @DisplayName("쓴 댓글: 최근 순으로 글 제목·블로그와 함께, 지운 댓글은 빠지고 공개를 끄면 남에게 막힌다 (D-94)")
    void commentList() throws Exception {
        long first = id(postAs("/api/posts/" + postId + "/comments", """
                {"content":"첫 댓글"}""", reader));
        long second = id(postAs("/api/posts/" + postId + "/comments", """
                {"content":"지울 댓글"}""", reader));
        id(postAs("/api/posts/" + postId + "/comments", """
                {"content":"답글","parentId":%d}""".formatted(first), reader));
        sendAs(HttpMethod.DELETE, "/api/comments/" + second, null, reader).andExpect(status().isOk());

        getAs("/api/members/" + readerId + "/comments")
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].content").value("답글"))
                .andExpect(jsonPath("$.items[0].reply").value(true))
                .andExpect(jsonPath("$.items[1].id").value(first))
                .andExpect(jsonPath("$.items[1].postTitle").value("첫 글"))
                .andExpect(jsonPath("$.items[1].blogSlug").value("food"));

        // 예전 화면처럼 comments 없이 보내면 댓글 공개 설정은 그대로
        sendAs(HttpMethod.PUT, "/api/me/activity-visibility", """
                {"posts":true,"likes":true,"following":true,"subscriptions":true}""", reader)
                .andExpect(jsonPath("$.comments").value(true));
        sendAs(HttpMethod.PUT, "/api/me/activity-visibility", """
                {"posts":true,"comments":false,"likes":true,"following":true,"subscriptions":true}""", reader)
                .andExpect(status().isOk());
        getAs("/api/members/" + readerId + "/comments", other).andExpect(status().isForbidden());
        getAs("/api/members/" + readerId + "/comments", reader).andExpect(jsonPath("$.total").value(2));
        getAs("/api/members/" + readerId).andExpect(jsonPath("$.visible.comments").value(false));
    }

    @Test
    @DisplayName("남에게는 그 사람이 볼 수 있는 글만 나온다 (D-91)")
    void privateBlogPostsHidden() throws Exception {
        postAs("/api/blogs", """
                {"slug":"secret","name":"비밀","visibility":"PRIVATE","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
        id(postAs("/api/blogs/secret/posts", """
                {"title":"비밀 글","content":"본문","tags":[]}""", owner));
        getAs("/api/members/" + ownerId + "/posts").andExpect(jsonPath("$.total").value(1));
        getAs("/api/members/" + ownerId + "/posts", owner).andExpect(jsonPath("$.total").value(2));
        getAs("/api/members/" + ownerId).andExpect(jsonPath("$.ownedBlogs.length()").value(1));
    }

    @Test
    @DisplayName("차단하면 서로의 팔로우가 풀리고 댓글이 숨겨지며 좋아요·참여를 못 한다. 같은 블로그 멤버끼리는 보인다 (D-36)")
    void block() throws Exception {
        postAs("/api/members/" + otherId + "/follow", null, reader).andExpect(status().isOk());
        postAs("/api/members/" + readerId + "/follow", null, other).andExpect(status().isOk());
        id(postAs("/api/posts/" + postId + "/comments", """
                {"content":"다른독자 댓글"}""", other));

        postAs("/api/members/" + otherId + "/block", null, reader).andExpect(jsonPath("$.blocked").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follows", Integer.class)).isZero();
        getAs("/api/posts/" + postId + "/comments", reader).andExpect(jsonPath("$.length()").value(0));
        getAs("/api/posts/" + postId + "/comments").andExpect(jsonPath("$.length()").value(1));
        postAs("/api/members/" + readerId + "/follow", null, other).andExpect(status().isForbidden());
        getAs("/api/me/blocks", reader).andExpect(jsonPath("$[0].nickname").value("다른독자"));

        // 같은 블로그의 멤버가 되면 그 블로그 안에서는 보인다
        postAs("/api/blogs/food/join", "{}", reader).andExpect(status().isOk());
        postAs("/api/blogs/food/join", "{}", other).andExpect(status().isOk());
        getAs("/api/posts/" + postId + "/comments", reader).andExpect(jsonPath("$.length()").value(1));

        // 블로그장이 차단한 회원은 좋아요·참여를 못 한다
        Cookie[] stranger = member("stranger@example.com", "낯선이");
        long strangerId = memberId("stranger@example.com");
        postAs("/api/members/" + strangerId + "/block", null, owner).andExpect(status().isOk());
        postAs("/api/posts/" + postId + "/like", null, stranger).andExpect(status().isForbidden());
        postAs("/api/blogs/food/join", "{}", stranger).andExpect(jsonPath("$.code").value("JOIN_BLOCKED"));
    }

    @Test
    @DisplayName("블로그 안의 글은 블로그장에게, 블로그장의 글·블로그·프로필 신고는 관리자에게. 같은 대상은 2주에 한 번 (US5)")
    void report() throws Exception {
        postAs("/api/blogs/food/join", "{}", other).andExpect(status().isOk());
        long memberPost = id(postAs("/api/blogs/food/posts", """
                {"title":"멤버 글","content":"본문","tags":[]}""", other));

        postAs("/api/reports", """
                {"targetType":"POST","targetId":%d,"reason":"SPAM"}""".formatted(memberPost), reader)
                .andExpect(jsonPath("$.handler").value("BLOG_OWNER"));
        postAs("/api/reports", """
                {"targetType":"POST","targetId":%d,"reason":"ABUSE"}""".formatted(postId), reader)
                .andExpect(jsonPath("$.handler").value("ADMIN"));
        postAs("/api/reports", """
                {"targetType":"BLOG","slug":"food","reason":"SPAM"}""", reader)
                .andExpect(jsonPath("$.handler").value("ADMIN"));
        postAs("/api/reports", """
                {"targetType":"MEMBER","targetId":%d,"reason":"SPAM"}""".formatted(otherId), reader)
                .andExpect(jsonPath("$.handler").value("ADMIN"));
        postAs("/api/reports", """
                {"targetType":"MEMBER","targetId":%d,"slug":"food","reason":"SPAM"}""".formatted(otherId), owner)
                .andExpect(jsonPath("$.handler").value("BLOG_OWNER"));

        postAs("/api/reports", """
                {"targetType":"POST","targetId":%d,"reason":"OBSCENE"}""".formatted(memberPost), reader)
                .andExpect(jsonPath("$.code").value("ALREADY_REPORTED"));
        postAs("/api/reports", """
                {"targetType":"POST","targetId":%d,"reason":"ETC"}""".formatted(postId), other)
                .andExpect(status().isBadRequest());
        postAs("/api/reports", """
                {"targetType":"POST","targetId":%d,"reason":"SPAM"}""".formatted(memberPost), other)
                .andExpect(jsonPath("$.code").value("SELF_REPORT"));

        clock.advance(Duration.ofDays(15));
        reader = login("reader@example.com", "Abcd1234!");
        postAs("/api/reports", """
                {"targetType":"POST","targetId":%d,"reason":"SPAM"}""".formatted(memberPost), reader)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("알림은 탭별로 보고 읽음·전체 삭제를 하며, 끌 수 없는 알림은 끄지 못한다 (SOC-04, SC-002)")
    void notificationsPanel() throws Exception {
        id(postAs("/api/posts/" + postId + "/comments", """
                {"content":"좋아요"}""", reader));
        postAs("/api/posts/" + postId + "/like", null, reader).andExpect(status().isOk());

        getAs("/api/notifications/unread", owner)
                .andExpect(jsonPath("$.ALL").value(2)).andExpect(jsonPath("$.COMMENT").value(1));
        getAs("/api/notifications?tab=COMMENT", owner)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].message").value("독자님이 내 글 '첫 글'에 댓글을 달았습니다."))
                .andExpect(jsonPath("$.items[0].link", containsString("/blog/food/posts/" + postId + "#comment-")));
        postAs("/api/notifications/read-all?tab=COMMENT", null, owner).andExpect(status().isOk());
        getAs("/api/notifications/unread", owner).andExpect(jsonPath("$.ALL").value(1));
        sendAs(HttpMethod.DELETE, "/api/notifications?tab=ALL", null, owner).andExpect(status().isOk());
        getAs("/api/notifications", owner).andExpect(jsonPath("$.total").value(0));

        sendAs(HttpMethod.PUT, "/api/me/notification-settings", """
                {"enabled":{"POST_DELETED":false}}""", owner).andExpect(status().isBadRequest());
        sendAs(HttpMethod.PUT, "/api/me/notification-settings", """
                {"enabled":{"LIKE":false},"retentionDays":7}""", owner)
                .andExpect(jsonPath("$.retentionDays").value(7));
        clock.advance(Duration.ofDays(1));
        other = login("other@example.com", "Abcd1234!");
        reader = login("reader@example.com", "Abcd1234!");
        postAs("/api/posts/" + postId + "/like", null, other).andExpect(status().isOk());
        // 좋아요 알림을 껐으니 만들어지지 않는다
        assertThat(notifications(ownerId, "LIKE")).isZero();
        getAs("/api/notifications/unread", reader).andExpect(status().isOk());
        getAs("/api/notifications/unread").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("보관 기간이 지난 알림은 매일 정리된다 (30일 또는 7일)")
    void cleanup() throws Exception {
        sendAs(HttpMethod.PUT, "/api/me/notification-settings", """
                {"retentionDays":7}""", owner).andExpect(status().isOk());
        postAs("/api/members/" + ownerId + "/follow", null, reader).andExpect(status().isOk());
        postAs("/api/members/" + readerId + "/follow", null, owner).andExpect(status().isOk());

        clock.advance(Duration.ofDays(8));
        cleanupJob.cleanup();
        assertThat(notifications(ownerId, "NEW_FOLLOWER")).isZero();
        assertThat(notifications(readerId, "NEW_FOLLOWER")).isEqualTo(1);
    }

    @Test
    @DisplayName("소개는 150자까지")
    void bio() throws Exception {
        sendAs(HttpMethod.PUT, "/api/me/bio", """
                {"bio":"맛집 다니는 사람"}""", reader).andExpect(status().isOk());
        getAs("/api/members/" + readerId).andExpect(jsonPath("$.bio").value("맛집 다니는 사람"));
        sendAs(HttpMethod.PUT, "/api/me/bio", "{\"bio\":\"" + "가".repeat(151) + "\"}", reader)
                .andExpect(status().isBadRequest());
    }
}
