package com.blognm.mainblog.notification;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 알림 저장 (SOC-04, 3.6 알림 표). 받는 사람이 끈 종류는 만들지 않는다. 끈 동안 생긴 일은 다시 켜도 만들어지지 않는다.
 * 계정·블로그 운영에 꼭 필요한 알림(끌 수 없음)은 설정과 상관없이 만든다 (D-56).
 */
@Component
public class NotificationWriter {

    /** 탭: 댓글 COMMENT, 좋아요 LIKE, 팔로우·구독 FOLLOW, 블로그 BLOG, 운영 ADMIN. setting은 켜고 끄는 단위. */
    public enum Type {
        COMMENT("COMMENT", "COMMENT", true, "내 글에 댓글"),
        REPLY("COMMENT", "REPLY", true, "내 댓글에 답글"),
        LIKE("LIKE", "LIKE", true, "내 글 좋아요"),
        NEW_FOLLOWER("FOLLOW", "NEW_FOLLOWER", true, "새 팔로워"),
        BLOG_SUBSCRIBED("FOLLOW", "BLOG_SUBSCRIBED", true, "내 블로그 구독"),
        JOIN_REQUESTED("BLOG", "JOIN_REQUESTED", true, "참여 신청"),
        JOIN_APPROVED("BLOG", "JOIN_RESULT", true, "참여 승인·거절"),
        JOIN_REJECTED("BLOG", "JOIN_RESULT", true, "참여 승인·거절"),
        POST_DELETED("ADMIN", "POST_DELETED", false, "내 글 삭제됨"),
        POST_HIDDEN("ADMIN", "POST_DELETED", false, "내 글 삭제됨"),
        // 6단계: 위임·폐쇄 (BLG-08·09), 제재 (BLG-11~13, ADM-02·07). 모두 끌 수 없다 (3.6)
        TRANSFER_REQUESTED("BLOG", "TRANSFER", false, "위임 요청·결과"),
        TRANSFER_ACCEPTED("BLOG", "TRANSFER", false, "위임 요청·결과"),
        TRANSFER_REJECTED("BLOG", "TRANSFER", false, "위임 요청·결과"),
        TRANSFER_EXPIRED("BLOG", "TRANSFER", false, "위임 요청·결과"),
        OWNER_PROMOTED("BLOG", "TRANSFER", false, "위임 요청·결과"),
        BLOG_CLOSING("BLOG", "CLOSING", false, "폐쇄 예정·철회"),
        BLOG_CLOSING_D3("BLOG", "CLOSING", false, "폐쇄 예정·철회"),
        BLOG_CLOSING_D1("BLOG", "CLOSING", false, "폐쇄 예정·철회"),
        BLOG_CLOSING_ADMIN("BLOG", "CLOSING", false, "폐쇄 예정·철회"),
        BLOG_CLOSING_KICK("BLOG", "CLOSING", false, "폐쇄 예정·철회"),
        BLOG_CLOSE_CANCELLED("BLOG", "CLOSING", false, "폐쇄 예정·철회"),
        MEMBER_WARNED("ADMIN", "SANCTION", false, "경고·정지·강제 퇴장"),
        MEMBER_SUSPENDED("ADMIN", "SANCTION", false, "경고·정지·강제 퇴장"),
        MEMBER_UNSUSPENDED("ADMIN", "SANCTION", false, "경고·정지·강제 퇴장"),
        MEMBER_KICKED("ADMIN", "SANCTION", false, "경고·정지·강제 퇴장"),
        OWNER_WARNED("ADMIN", "OWNER_SANCTION", false, "블로그장 경고·권한 박탈"),
        OWNER_KICKED("ADMIN", "OWNER_SANCTION", false, "블로그장 경고·권한 박탈"),
        BLOG_HIDDEN("ADMIN", "OWNER_SANCTION", false, "블로그장 경고·권한 박탈"),
        BLOG_UNHIDDEN("ADMIN", "OWNER_SANCTION", false, "블로그장 경고·권한 박탈"),
        BLACKLIST_APPEAL("BLOG", "JOIN_REQUESTED", true, "참여 신청"),
        BLACKLIST_LIFTED("ADMIN", "APPEAL_RESULT", false, "블랙리스트 해제 문의 결과"),
        BLACKLIST_REJECTED("ADMIN", "APPEAL_RESULT", false, "블랙리스트 해제 문의 결과"),
        REPORT_RESULT("ADMIN", "REPORT_RESULT", true, "신고 처리 결과"),
        NOTICE("ADMIN", "NOTICE", true, "공지사항");

        public final String tab;
        public final String setting;
        public final boolean switchable;
        public final String label;

        Type(String tab, String setting, boolean switchable, String label) {
            this.tab = tab;
            this.setting = setting;
            this.switchable = switchable;
            this.label = label;
        }
    }

    /** 내 정보 > 알림 설정에 보여 줄 끌 수 없는 알림 (6단계에서 만드는 것 포함, 3.6 알림 표). */
    public static final List<String> ALWAYS_ON = List.of("내 글 삭제됨", "위임 요청·결과", "폐쇄 예정·철회", "비공개 전환",
            "블랙리스트 해제 문의 결과", "경고·정지·강제 퇴장", "블로그장 경고·권한 박탈");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 블로그 탭 알림. 대상은 그 블로그다. */
    public void blog(long recipientId, Type type, long blogId, Long actorId) {
        save(recipientId, type, blogId, actorId, "BLOG", blogId);
    }

    /** 블로그 탭 알림에 문구용 짧은 값(정지 기간, 폐쇄 날짜 등)을 붙인다. */
    public void blog(long recipientId, Type type, long blogId, Long actorId, String detail) {
        save(recipientId, type, blogId, actorId, "BLOG", blogId, detail);
    }

    /** 신고 처리 결과. detail은 결과 이름 (ADM-04, BLG-13). */
    public void reportResult(long recipientId, Long blogId, long reportId, String detail) {
        save(recipientId, Type.REPORT_RESULT, blogId, null, "REPORT", reportId, detail);
    }

    /** 대상이 글인 알림. 블로그장이 내 글을 지웠을 때 등 (D-58). */
    public void post(long recipientId, Type type, long blogId, Long actorId, long postId) {
        save(recipientId, type, blogId, actorId, "POST", postId);
    }

    /** 댓글 탭 알림. 내 글에 댓글(COMMENT), 내 댓글에 답글(REPLY). 대상은 댓글이다. */
    public void comment(long recipientId, Type type, long blogId, long actorId, long commentId) {
        save(recipientId, type, blogId, actorId, "COMMENT", commentId);
    }

    /** 새 팔로워. 대상은 팔로우한 회원이다. */
    public void follower(long recipientId, long actorId) {
        save(recipientId, Type.NEW_FOLLOWER, null, actorId, "MEMBER", actorId);
    }

    /** 좋아요 탭 알림. 취소했다 다시 눌러도 같은 사람·같은 글은 하루 한 번만 보낸다 (추천안 #11). */
    public void like(long recipientId, long blogId, long actorId, long postId) {
        if (sentToday(recipientId, Type.LIKE, actorId, postId)) {
            return;
        }
        save(recipientId, Type.LIKE, blogId, actorId, "POST", postId);
    }

    /** 팔로우·구독도 껐다 켰다 해서 알림을 쏟아내지 않게 하루 한 번만 보낸다. */
    public void onceADay(long recipientId, Type type, Long blogId, long actorId, String targetType, long targetId) {
        if (sentToday(recipientId, type, actorId, targetId)) {
            return;
        }
        save(recipientId, type, blogId, actorId, targetType, targetId);
    }

    public boolean enabled(long recipientId, Type type) {
        if (!type.switchable) {
            return true;
        }
        List<Boolean> rows = jdbc.queryForList("SELECT enabled FROM notification_settings WHERE member_id = ? AND type = ?",
                Boolean.class, recipientId, type.setting);
        return rows.isEmpty() || rows.get(0);
    }

    private boolean sentToday(long recipientId, Type type, long actorId, long targetId) {
        Integer already = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE recipient_id = ? AND type = ? AND actor_id = ? AND target_id = ? AND created_at >= ?
                """, Integer.class, recipientId, type.name(), actorId, targetId,
                LocalDateTime.now(clock).toLocalDate().atStartOfDay());
        return already != null && already > 0;
    }

    private void save(long recipientId, Type type, Long blogId, Long actorId, String targetType, long targetId) {
        save(recipientId, type, blogId, actorId, targetType, targetId, null);
    }

    private void save(long recipientId, Type type, Long blogId, Long actorId, String targetType, long targetId,
            String detail) {
        if (!enabled(recipientId, type)) {
            return;
        }
        jdbc.update("""
                INSERT INTO notifications (recipient_id, type, tab, blog_id, actor_id, target_type, target_id, detail,
                                           created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, recipientId, type.name(), type.tab, blogId, actorId, targetType, targetId, detail,
                LocalDateTime.now(clock));
    }
}
