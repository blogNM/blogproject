package com.blognm.mainblog.blog;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.notification.NotificationWriter;
import com.blognm.mainblog.notification.NotificationWriter.Type;

/**
 * 블로그장 위임(BLG-08)과 폐쇄(BLG-09), 메인 관리자의 강제 폐쇄·블로그장 경고·강퇴·숨김(ADM-02·07).
 * 폐쇄는 누른 날짜 + 7일 04:00(D-12)에 매일 04:00 배치가 처리한다. 그동안 블로그는 평소처럼 쓰인다 (D-67).
 */
@Service
public class BlogLifecycleService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BlogLifecycleService.class);

    /** 위임 요청은 7일 동안 답이 없으면 자동 취소 (D-31). */
    static final int TRANSFER_DAYS = 7;
    static final int CLOSE_DAYS = 7;
    /** 블로그장 경고 3번이면 권한 박탈. 최근 1년 안의 경고만 센다 (ADM-07, D-87). */
    public static final int OWNER_WARNING_LIMIT = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final JdbcTemplate jdbc;
    private final BlogService blogService;
    private final NotificationWriter notifications;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BlogLifecycleService(JdbcTemplate jdbc, BlogService blogService, NotificationWriter notifications,
            Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.blogService = blogService;
        this.notifications = notifications;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ----- 위임 (BLG-08) -----

    /** 블로그장이 멤버에게 블로그장을 넘기자고 한다. 폐쇄 예정 중에는 못 하고(D-20), 기다리는 요청은 하나뿐이다. */
    @Transactional
    public void requestTransfer(String slug, long ownerId, long toMemberId) {
        BlogRow b = lockBlog(slug);
        if ("CLOSING".equals(b.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "BLOG_CLOSING", "폐쇄 예정 중에는 위임할 수 없습니다. 먼저 폐쇄를 철회해 주세요.");
        }
        if (toMemberId == ownerId) {
            throw ApiException.badRequest("SELF_TRANSFER", "다른 멤버를 골라 주세요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> target = jdbc.queryForList("""
                SELECT suspended_until FROM blog_memberships WHERE blog_id = ? AND member_id = ? AND status = 'ACTIVE'
                """, b.id(), toMemberId);
        if (target.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "이 블로그의 멤버가 아닙니다.");
        }
        LocalDateTime until = (LocalDateTime) target.get(0).get("suspended_until");
        if (until != null && until.isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMBER_SUSPENDED", "정지 중인 멤버에게는 넘길 수 없습니다.");
        }
        if (pendingTransfer(b.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_PENDING", "이미 기다리는 위임 요청이 있습니다. 먼저 취소해 주세요.");
        }
        jdbc.update("""
                INSERT INTO ownership_transfers (blog_id, from_member_id, to_member_id, status, requested_at)
                VALUES (?, ?, ?, 'PENDING', ?)
                """, b.id(), ownerId, toMemberId, now);
        notifications.blog(toMemberId, Type.TRANSFER_REQUESTED, b.id(), ownerId, null);
    }

    @Transactional
    public void cancelTransfer(String slug) {
        BlogRow b = lockBlog(slug);
        if (jdbc.update("UPDATE ownership_transfers SET status = 'CANCELLED', responded_at = ? WHERE blog_id = ? AND status = 'PENDING'",
                LocalDateTime.now(clock), b.id()) == 0) {
            throw ApiException.badRequest("NO_PENDING_TRANSFER", "기다리는 위임 요청이 없습니다.");
        }
    }

    /**
     * 받는 멤버가 수락하면 블로그장이 바뀐다. 전 블로그장은 일반 멤버로 남는다.
     * 받는 사람의 블로그 개수 제한(BLG-10)을 넘으면 수락할 수 없다.
     */
    @Transactional
    public void respondTransfer(String slug, long memberId, boolean accept) {
        BlogRow b = lockBlog(slug);
        Transfer t = pendingTransfer(b.id()).filter(x -> x.toMemberId() == memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "받은 위임 요청이 없습니다."));
        LocalDateTime now = LocalDateTime.now(clock);
        if (!accept) {
            jdbc.update("UPDATE ownership_transfers SET status = 'REJECTED', responded_at = ? WHERE id = ?", now, t.id());
            notifications.blog(t.fromMemberId(), Type.TRANSFER_REJECTED, b.id(), memberId, null);
            return;
        }
        blogService.checkOwnerLimit(memberId, b.id());
        changeOwner(b.id(), t.fromMemberId(), memberId);
        jdbc.update("UPDATE ownership_transfers SET status = 'ACCEPTED', responded_at = ? WHERE id = ?", now, t.id());
        notifications.blog(t.fromMemberId(), Type.TRANSFER_ACCEPTED, b.id(), memberId, null);
    }

    /** 기다리는 위임 요청. 블로그 화면에서 받는 사람에게 [수락]·[거절]을, 블로그장에게 [취소]를 보인다. */
    @Transactional(readOnly = true)
    public Optional<Transfer> pendingTransfer(long blogId) {
        return jdbc.query("""
                SELECT t.id, t.from_member_id, t.to_member_id, t.requested_at, m.nickname
                FROM ownership_transfers t JOIN members m ON m.id = t.to_member_id
                WHERE t.blog_id = ? AND t.status = 'PENDING' ORDER BY t.id DESC LIMIT 1
                """, (rs, i) -> new Transfer(rs.getLong("id"), rs.getLong("from_member_id"), rs.getLong("to_member_id"),
                rs.getString("nickname"), rs.getObject("requested_at", LocalDateTime.class)), blogId).stream().findFirst();
    }

    private void changeOwner(long blogId, long fromId, long toId) {
        jdbc.update("""
                UPDATE blog_memberships SET role = 'MEMBER', perm_edit_info = FALSE, perm_manage_members = FALSE,
                       perm_manage_posts = FALSE, sub_owner_since = NULL
                WHERE blog_id = ? AND member_id = ?
                """, blogId, fromId);
        jdbc.update("""
                UPDATE blog_memberships SET role = 'OWNER', perm_edit_info = FALSE, perm_manage_members = FALSE,
                       perm_manage_posts = FALSE, sub_owner_since = NULL, suspended_until = NULL
                WHERE blog_id = ? AND member_id = ?
                """, blogId, toId);
        jdbc.update("UPDATE blogs SET owner_id = ? WHERE id = ?", toId, blogId);
    }

    // ----- 폐쇄 (BLG-09, D-86) -----

    /** 블로그장이 닫는다. 기다리는 위임 요청은 자동 취소된다. */
    @Transactional
    public String close(String slug, long ownerId) {
        BlogRow b = lockBlog(slug);
        return scheduleClose(b, "OWNER", Type.BLOG_CLOSING, ownerId);
    }

    /** 폐쇄 철회. 블로그장만, 관리자가 건 강제 폐쇄도 블로그장이 철회한다 (D-86). 블로그장 강퇴로 인한 폐쇄는 철회할 사람이 없다. */
    @Transactional
    public void cancelClose(String slug, long ownerId) {
        BlogRow b = lockBlog(slug);
        if (!"CLOSING".equals(b.status())) {
            throw ApiException.badRequest("NOT_CLOSING", "폐쇄 예정인 블로그가 아닙니다.");
        }
        jdbc.update("""
                UPDATE blogs SET status = 'ACTIVE', close_scheduled_at = NULL, close_requested_by = NULL WHERE id = ?
                """, b.id());
        jdbc.update("""
                INSERT IGNORE INTO blog_closing_notices (blog_id, close_scheduled_at, stage, sent_at) VALUES (?, ?, 'CANCELLED', ?)
                """, b.id(), b.closeAt(), LocalDateTime.now(clock));
        closingAudience(b.id(), true).forEach(id -> notifications.blog(id, Type.BLOG_CLOSE_CANCELLED, b.id(), ownerId, null));
    }

    /** 메인 관리자의 강제 폐쇄. 블로그장 폐쇄와 같이 7일 뒤 04:00이고, 그 사이 블로그장이 철회할 수 있다 (D-86). */
    @Transactional
    public String closeByAdmin(long blogId) {
        BlogRow b = lockBlog(blogId);
        return scheduleClose(b, "ADMIN", Type.BLOG_CLOSING_ADMIN, null);
    }

    private String scheduleClose(BlogRow b, String by, Type type, Long actorId) {
        if ("CLOSING".equals(b.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CLOSING", "이미 폐쇄 예정인 블로그입니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime closeAt = LocalDate.now(clock).plusDays(CLOSE_DAYS).atTime(4, 0);
        jdbc.update("UPDATE blogs SET status = 'CLOSING', close_scheduled_at = ?, close_requested_by = ? WHERE id = ?",
                closeAt, by, b.id());
        jdbc.update("UPDATE ownership_transfers SET status = 'CANCELLED', responded_at = ? WHERE blog_id = ? AND status = 'PENDING'",
                now, b.id());
        jdbc.update("""
                INSERT IGNORE INTO blog_closing_notices (blog_id, close_scheduled_at, stage, sent_at) VALUES (?, ?, 'IMMEDIATE', ?)
                """, b.id(), closeAt, now);
        // 블로그장이 직접 닫으면 블로그장에게는 알리지 않는다 (BLG-09)
        closingAudience(b.id(), !"OWNER".equals(by))
                .forEach(id -> notifications.blog(id, type, b.id(), actorId, closeAt.format(DATE)));
        return closeAt.format(DATE);
    }

    /** 폐쇄 알림을 받는 사람: 블로그장을 뺀 멤버 전원과 구독자 (D-14). withOwner면 블로그장도. */
    private Set<Long> closingAudience(long blogId, boolean withOwner) {
        Set<Long> ids = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT member_id FROM blog_memberships WHERE blog_id = ? AND status = 'ACTIVE'" + (withOwner ? "" : " AND role <> 'OWNER'"),
                Long.class, blogId));
        ids.addAll(jdbc.queryForList("""
                SELECT s.member_id FROM subscriptions s JOIN members m ON m.id = s.member_id
                WHERE s.blog_id = ? AND m.status = 'ACTIVE'
                """, Long.class, blogId));
        if (!withOwner) {
            ids.removeAll(jdbc.queryForList("SELECT owner_id FROM blogs WHERE id = ?", Long.class, blogId));
        }
        return ids;
    }

    // ----- 매일 04:00 배치 (BlogLifecycleJob이 부른다) -----

    /** 예정 시각이 지난 블로그를 닫는다. 서버가 꺼졌던 날의 몫도 다음 배치가 처리한다. */
    @Transactional
    public int closeDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        return jdbc.update("""
                UPDATE blogs SET status = 'CLOSED', closed_at = ? WHERE status = 'CLOSING' AND close_scheduled_at <= ?
                """, now, now);
    }

    /** 3일 전·1일 전 알림. 이미 보낸 단계는 다시 보내지 않고, 밀렸으면 가장 가까운 단계 하나만 보낸다 (SC-003). */
    @Transactional
    public int sendReminders() {
        LocalDateTime now = LocalDateTime.now(clock);
        int sent = 0;
        List<Map<String, Object>> closing = jdbc.queryForList(
                "SELECT id, close_scheduled_at FROM blogs WHERE status = 'CLOSING' AND close_scheduled_at > ?", now);
        for (Map<String, Object> row : closing) {
            long blogId = ((Number) row.get("id")).longValue();
            LocalDateTime closeAt = (LocalDateTime) row.get("close_scheduled_at");
            String stage = !now.isBefore(closeAt.minusDays(1)) ? "D1" : !now.isBefore(closeAt.minusDays(3)) ? "D3" : null;
            if (stage == null) {
                continue;
            }
            int inserted = jdbc.update("""
                    INSERT IGNORE INTO blog_closing_notices (blog_id, close_scheduled_at, stage, sent_at) VALUES (?, ?, ?, ?)
                    """, blogId, closeAt, stage, now);
            if ("D1".equals(stage)) {
                // D1을 보냈으면 밀린 D3은 보내지 않는다
                jdbc.update("""
                        INSERT IGNORE INTO blog_closing_notices (blog_id, close_scheduled_at, stage, sent_at) VALUES (?, ?, 'D3', ?)
                        """, blogId, closeAt, now);
            }
            if (inserted == 1) {
                Type type = "D1".equals(stage) ? Type.BLOG_CLOSING_D1 : Type.BLOG_CLOSING_D3;
                closingAudience(blogId, false).forEach(id -> notifications.blog(id, type, blogId, null, closeAt.format(DATE)));
                sent++;
            }
        }
        return sent;
    }

    /** 7일 동안 답이 없는 위임 요청은 취소하고 블로그장에게 알린다 (D-31). */
    @Transactional
    public int expireTransfers() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, blog_id, from_member_id FROM ownership_transfers WHERE status = 'PENDING' AND requested_at <= ?",
                now.minusDays(TRANSFER_DAYS));
        for (Map<String, Object> r : rows) {
            jdbc.update("UPDATE ownership_transfers SET status = 'EXPIRED', responded_at = ? WHERE id = ?", now, r.get("id"));
            notifications.blog(((Number) r.get("from_member_id")).longValue(), Type.TRANSFER_EXPIRED,
                    ((Number) r.get("blog_id")).longValue(), null, null);
        }
        return rows.size();
    }

    /**
     * 닫힌 지 30일 지난 블로그의 글·댓글·사진·카테고리를 지우고 주소를 풀어 준다 (D-35, 4.5).
     * 블로그 행은 신고·제재 기록이 가리키므로 남기고, 주소만 쓸 수 없는 값으로 바꾼다.
     *
     * 블로그 하나씩 따로 저장한다. 정리할 블로그가 많아도 표를 오래 잠그지 않고, 하나가 실패해도 나머지는 정리된다.
     *
     * @return 지운 사진 파일 이름 (DB 커밋 뒤에 파일을 지운다)
     */
    public List<String> purgeClosed() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM blogs WHERE status = 'CLOSED' AND purged_at IS NULL AND closed_at <= ?", Long.class,
                now.minusDays(30));
        List<String> files = new java.util.ArrayList<>();
        for (long id : ids) {
            try {
                files.addAll(tx.execute(status -> purgeOne(id, now)));
            } catch (RuntimeException e) {
                log.warn("닫힌 블로그 정리 실패: blog {} - {}", id, e.toString());
            }
        }
        return files;
    }

    private List<String> purgeOne(long id, LocalDateTime now) {
        List<String> files = new java.util.ArrayList<>(jdbc.queryForList("""
                SELECT i.stored_name FROM images i JOIN posts p ON p.id = i.post_id WHERE p.blog_id = ?
                UNION SELECT i.stored_name FROM images i JOIN blogs b ON b.cover_image_id = i.id WHERE b.id = ?
                """, String.class, id, id));
        jdbc.update("UPDATE blogs SET cover_image_id = NULL WHERE id = ?", id);
        jdbc.update("DELETE i FROM images i JOIN posts p ON p.id = i.post_id WHERE p.blog_id = ?", id);
        jdbc.update("DELETE c FROM comments c JOIN posts p ON p.id = c.post_id WHERE p.blog_id = ? AND c.parent_id IS NOT NULL", id);
        jdbc.update("DELETE c FROM comments c JOIN posts p ON p.id = c.post_id WHERE p.blog_id = ?", id);
        jdbc.update("DELETE l FROM post_likes l JOIN posts p ON p.id = l.post_id WHERE p.blog_id = ?", id);
        jdbc.update("DELETE v FROM post_views v JOIN posts p ON p.id = v.post_id WHERE p.blog_id = ?", id);
        jdbc.update("DELETE t FROM post_tags t JOIN posts p ON p.id = t.post_id WHERE p.blog_id = ?", id);
        jdbc.update("DELETE FROM posts WHERE blog_id = ?", id);
        jdbc.update("DELETE FROM categories WHERE blog_id = ?", id);
        jdbc.update("DELETE FROM blog_tags WHERE blog_id = ?", id);
        jdbc.update("DELETE FROM subscriptions WHERE blog_id = ?", id);
        jdbc.update("DELETE FROM join_requests WHERE blog_id = ?", id);
        jdbc.update("DELETE FROM blog_visits WHERE blog_id = ?", id);
        // 주소 규칙(영문 소문자 시작)에 맞지 않는 값이라 다시 만들 수 없고, 원래 주소는 풀린다
        jdbc.update("UPDATE blogs SET slug = CONCAT('_closed-', id), share_token = NULL, purged_at = ? WHERE id = ?",
                now, id);
        return files;
    }

    // ----- 메인 관리자 (ADM-02, ADM-07) -----

    /** 숨김: 목록·검색·피드에서 빠지고 멤버가 아니면 주소로도 못 본다. 블로그장에게 알린다 (추천안 #17, 확인 대기). */
    @Transactional
    public void setHidden(long blogId, boolean hidden) {
        BlogRow b = lockBlog(blogId);
        if (b.hidden() == hidden) {
            throw new ApiException(HttpStatus.CONFLICT, hidden ? "ALREADY_HIDDEN" : "NOT_HIDDEN",
                    hidden ? "이미 숨긴 블로그입니다." : "숨긴 블로그가 아닙니다.");
        }
        jdbc.update("UPDATE blogs SET is_hidden = ? WHERE id = ?", hidden, blogId);
        notifications.blog(b.ownerId(), hidden ? Type.BLOG_HIDDEN : Type.BLOG_UNHIDDEN, blogId, null, null);
    }

    /**
     * 블로그장 경고. 최근 1년 경고가 3번이 되면 권한을 박탈한다 (ADM-07, D-87).
     *
     * @return 이번 경고를 포함한 최근 1년 경고 수
     */
    @Transactional
    public OwnerWarning warnOwner(long blogId, long adminId, SanctionReason code, String reason, Long reportId) {
        SanctionReason why = SanctionReason.require(code);
        BlogRow b = lockBlog(blogId);
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("""
                INSERT INTO owner_warnings (blog_id, member_id, admin_id, report_id, reason_code, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, blogId, b.ownerId(), adminId, reportId, why.name(), SanctionService.reason(reason), now);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM owner_warnings WHERE blog_id = ? AND member_id = ? AND created_at >= ?",
                Integer.class, blogId, b.ownerId(), now.minusYears(1));
        int count = n == null ? 0 : n;
        notifications.blog(b.ownerId(), Type.OWNER_WARNED, blogId, null,
                count + "/" + OWNER_WARNING_LIMIT + " · 사유: " + why.label);
        boolean kicked = false;
        if (count >= OWNER_WARNING_LIMIT) {
            kickOwner(b);
            kicked = true;
        }
        return new OwnerWarning(count, kicked);
    }

    /** 블로그장 강퇴(권한 박탈). 가장 먼저 부블로그장이 된 사람에게 넘기고, 없으면 7일 뒤 04:00 폐쇄 (D-17, D-30, D-54). */
    @Transactional
    public String kickOwner(long blogId) {
        return kickOwner(lockBlog(blogId));
    }

    private String kickOwner(BlogRow b) {
        if (!"ACTIVE".equals(b.status()) && !"CLOSING".equals(b.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "BLOG_CLOSED", "닫힌 블로그입니다.");
        }
        long oldOwner = b.ownerId();
        notifications.blog(oldOwner, Type.OWNER_KICKED, b.id(), null, null);
        Optional<Long> next = jdbc.queryForList("""
                SELECT member_id FROM blog_memberships WHERE blog_id = ? AND status = 'ACTIVE' AND role = 'SUB_OWNER'
                ORDER BY sub_owner_since, id LIMIT 1
                """, Long.class, b.id()).stream().findFirst();
        jdbc.update("UPDATE ownership_transfers SET status = 'CANCELLED', responded_at = ? WHERE blog_id = ? AND status = 'PENDING'",
                LocalDateTime.now(clock), b.id());
        if (next.isPresent()) {
            changeOwner(b.id(), oldOwner, next.get());
            notifications.blog(next.get(), Type.OWNER_PROMOTED, b.id(), null, null);
            return "TRANSFERRED";
        }
        // 넘길 사람이 없으면 박탈된 블로그장은 일반 멤버로 남고 블로그는 폐쇄 예정이 된다
        jdbc.update("""
                UPDATE blog_memberships SET role = 'MEMBER' WHERE blog_id = ? AND member_id = ?
                """, b.id(), oldOwner);
        if (!"CLOSING".equals(b.status())) {
            scheduleClose(b, "OWNER_KICKED", Type.BLOG_CLOSING_KICK, null);
        } else {
            jdbc.update("UPDATE blogs SET close_requested_by = 'OWNER_KICKED' WHERE id = ?", b.id());
        }
        return "CLOSING";
    }

    // ----- 공통 -----

    private BlogRow lockBlog(String slug) {
        return jdbc.query("SELECT id, owner_id, status, is_hidden, close_scheduled_at FROM blogs WHERE slug = ? FOR UPDATE",
                BlogLifecycleService::blogRow, slug).stream().findFirst().filter(b -> !"CLOSED".equals(b.status()))
                .orElseThrow(BlogService::notFound);
    }

    private BlogRow lockBlog(long id) {
        return jdbc.query("SELECT id, owner_id, status, is_hidden, close_scheduled_at FROM blogs WHERE id = ? FOR UPDATE",
                BlogLifecycleService::blogRow, id).stream().findFirst().filter(b -> !"CLOSED".equals(b.status()))
                .orElseThrow(BlogService::notFound);
    }

    private static BlogRow blogRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new BlogRow(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("status"), rs.getBoolean("is_hidden"),
                rs.getObject("close_scheduled_at", LocalDateTime.class));
    }

    private record BlogRow(long id, long ownerId, String status, boolean hidden, LocalDateTime closeAt) {
    }

    public record Transfer(long id, long fromMemberId, long toMemberId, String toNickname, LocalDateTime requestedAt) {
    }

    public record OwnerWarning(int count, boolean kicked) {
    }
}
