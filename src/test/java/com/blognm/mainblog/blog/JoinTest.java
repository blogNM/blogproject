package com.blognm.mainblog.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.blognm.mainblog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 002 User Story 2·3 - 참여 신청·승인, 내 블로그, 블로그 탈퇴 */
class JoinTest extends IntegrationTest {

    private Cookie[] owner;
    private Cookie[] applicant;

    @BeforeEach
    void setUp() throws Exception {
        owner = member("owner@example.com", "블로그장");
        applicant = member("b@example.com", "신청자");
        postAs("/api/blogs", """
                {"slug":"free-blog","name":"자유 블로그","visibility":"PUBLIC","joinPolicy":"FREE"}""", owner)
                .andExpect(status().isOk());
        postAs("/api/blogs", """
                {"slug":"strict-blog","name":"승인제 블로그","visibility":"PUBLIC","joinPolicy":"APPROVAL"}""", owner)
                .andExpect(status().isOk());
    }

    private long pendingRequestId(String slug) throws Exception {
        String body = getAs("/api/blogs/" + slug + "/join-requests", owner).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$[0].id")).longValue();
    }

    @Test
    @DisplayName("자유 참여 블로그는 신청하면 바로 멤버가 된다")
    void freeJoin() throws Exception {
        postAs("/api/blogs/free-blog/join", null, applicant).andExpect(jsonPath("$.joined").value(true));
        getAs("/api/blogs/free-blog", applicant).andExpect(jsonPath("$.myRole").value("MEMBER"))
                .andExpect(jsonPath("$.memberCount").value(2));
        getAs("/api/me/blogs", applicant).andExpect(jsonPath("$.joined[0].slug").value("free-blog"))
                .andExpect(jsonPath("$.owned.length()").value(0));
        postAs("/api/blogs/free-blog/join", null, applicant).andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    @Test
    @DisplayName("승인제: 신청 → 블로그장이 승인 → 신청자의 내 블로그에 보인다. 알림도 남는다")
    void approvalFlow() throws Exception {
        postAs("/api/blogs/strict-blog/join", null, applicant).andExpect(jsonPath("$.requested").value(true));
        getAs("/api/blogs/strict-blog", applicant).andExpect(jsonPath("$.myRequest").value("PENDING"));
        getAs("/api/me/blogs", applicant).andExpect(jsonPath("$.pending[0].slug").value("strict-blog"));
        postAs("/api/blogs/strict-blog/join", null, applicant)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REQUESTED"));

        getAs("/api/blogs/strict-blog/join-requests", owner).andExpect(jsonPath("$[0].nickname").value("신청자"));
        postAs("/api/blogs/strict-blog/join-requests/" + pendingRequestId("strict-blog") + "/approve", null, owner)
                .andExpect(status().isOk());

        getAs("/api/me/blogs", applicant).andExpect(jsonPath("$.joined[0].slug").value("strict-blog"))
                .andExpect(jsonPath("$.pending.length()").value(0));
        assertThat(jdbc.queryForList("SELECT type FROM notifications ORDER BY id", String.class))
                .containsExactly("JOIN_REQUESTED", "JOIN_APPROVED");
    }

    @Test
    @DisplayName("거절되면 7일 안에는 다시 신청할 수 없고, 7일 뒤에는 된다")
    void rejectedWaitsSevenDays() throws Exception {
        postAs("/api/blogs/strict-blog/join", null, applicant);
        postAs("/api/blogs/strict-blog/join-requests/" + pendingRequestId("strict-blog") + "/reject", null, owner)
                .andExpect(status().isOk());
        getAs("/api/blogs/strict-blog", applicant).andExpect(jsonPath("$.myRequest").value("REJECTED"));

        clock.advance(Duration.ofDays(6));
        applicant = login("b@example.com", "Abcd1234!");
        postAs("/api/blogs/strict-blog/join", null, applicant)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REJECTED_RECENTLY"));
        clock.advance(Duration.ofDays(1).plusMinutes(1));
        applicant = login("b@example.com", "Abcd1234!");
        postAs("/api/blogs/strict-blog/join", null, applicant).andExpect(jsonPath("$.requested").value(true));
    }

    @Test
    @DisplayName("대기 중인 신청은 취소할 수 있다")
    void cancelRequest() throws Exception {
        postAs("/api/blogs/strict-blog/join", null, applicant);
        sendAs(HttpMethod.DELETE, "/api/blogs/strict-blog/join", null, applicant).andExpect(status().isOk());
        getAs("/api/blogs/strict-blog/join-requests", owner).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("멤버는 참여 신청을 처리할 수 없다")
    void memberCannotApprove() throws Exception {
        Cookie[] member = member("m@example.com", "그냥멤버");
        postAs("/api/blogs/free-blog/join", null, member);
        postAs("/api/blogs/strict-blog/join", null, applicant);
        long id = pendingRequestId("strict-blog");

        getAs("/api/blogs/strict-blog/join-requests", member).andExpect(status().isForbidden());
        postAs("/api/blogs/strict-blog/join-requests/" + id + "/approve", null, member)
                .andExpect(status().isForbidden());
        getAs("/api/blogs/free-blog/members", member).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A 블로그의 블로그장은 B 블로그의 신청을 처리할 수 없다 (SEC-07)")
    void otherOwnerCannotApprove() throws Exception {
        Cookie[] otherOwner = member("c@example.com", "남의블로그장");
        postAs("/api/blogs", """
                {"slug":"other-blog","name":"남의 블로그","visibility":"PUBLIC","joinPolicy":"APPROVAL"}""", otherOwner);
        postAs("/api/blogs/strict-blog/join", null, applicant);
        long id = pendingRequestId("strict-blog");

        // 자기 블로그 주소로 남의 신청 번호를 넣어도 처리되지 않는다
        postAs("/api/blogs/other-blog/join-requests/" + id + "/approve", null, otherOwner)
                .andExpect(status().isNotFound());
        postAs("/api/blogs/strict-blog/join-requests/" + id + "/approve", null, otherOwner)
                .andExpect(status().isForbidden());
        getAs("/api/blogs/strict-blog", applicant).andExpect(jsonPath("$.myRequest").value("PENDING"));
    }

    @Test
    @DisplayName("멤버는 블로그에서 나가고 바로 다시 참여할 수 있다. 블로그장은 나갈 수 없다")
    void leave() throws Exception {
        postAs("/api/blogs/free-blog/join", null, applicant);
        postAs("/api/blogs/free-blog/leave", null, applicant).andExpect(status().isOk());
        getAs("/api/me/blogs", applicant).andExpect(jsonPath("$.joined.length()").value(0));
        getAs("/api/blogs/free-blog", applicant).andExpect(jsonPath("$.myRole").doesNotExist());

        postAs("/api/blogs/free-blog/join", null, applicant).andExpect(jsonPath("$.joined").value(true));
        postAs("/api/blogs/free-blog/leave", null, owner)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_CANNOT_LEAVE"));
    }

    @Test
    @DisplayName("멤버 목록은 블로그장에게 이메일·전화번호를 가려서 보여준다 (4.4)")
    void membersMasked() throws Exception {
        postAs("/api/blogs/free-blog/join", null, applicant);
        getAs("/api/blogs/free-blog/members", owner)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].nickname").value("신청자"))
                .andExpect(jsonPath("$[1].email").value("b***@example.com"))
                .andExpect(jsonPath("$[1].phone").value("010****5678"));
    }

    @Test
    @DisplayName("멤버 관리 권한이 있는 부블로그장은 신청을 처리할 수 있다")
    void subOwnerWithPermission() throws Exception {
        Cookie[] sub = member("sub@example.com", "부블로그장");
        postAs("/api/blogs/strict-blog/join", null, sub);
        postAs("/api/blogs/strict-blog/join-requests/" + pendingRequestId("strict-blog") + "/approve", null, owner);
        jdbc.update("""
                UPDATE blog_memberships SET role = 'SUB_OWNER', perm_manage_members = TRUE
                WHERE member_id = (SELECT id FROM members WHERE nickname = '부블로그장')""");

        postAs("/api/blogs/strict-blog/join", null, applicant);
        postAs("/api/blogs/strict-blog/join-requests/" + pendingRequestId("strict-blog") + "/approve", null, sub)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("정지된 멤버는 그 블로그에 들어갈 수 없다")
    void suspendedMember() throws Exception {
        postAs("/api/blogs/free-blog/join", null, applicant);
        jdbc.update("""
                UPDATE blog_memberships SET suspended_until = '9999-12-31 00:00:00'
                WHERE member_id = (SELECT id FROM members WHERE nickname = '신청자')""");
        getAs("/api/blogs/free-blog", applicant).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUSPENDED"));
    }
}
