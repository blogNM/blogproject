package com.blognm.mainblog.blog;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.DetailedApiException;
import com.blognm.mainblog.common.Hashing;
import com.blognm.mainblog.common.Masking;
import com.blognm.mainblog.config.AppProperties;
import com.blognm.mainblog.notification.NotificationWriter;
import com.blognm.mainblog.notification.NotificationWriter.Type;

/**
 * 블로그 안의 멤버 관리 (BLG-11~13, D-43, D-46, D-55, D-57, D-87).
 * 부블로그장 지정과 권한 체크(D-71), 경고·정지·해제·강제 퇴장, 블랙리스트와 해제 문의.
 * 권한(블로그장 또는 멤버 관리 권한이 있는 부블로그장)은 컨트롤러의 @PreAuthorize가 먼저 확인하고,
 * 여기서는 대상이 누구인지(블로그장·자기 자신·다른 부블로그장)를 다시 본다.
 */
@Service
public class SanctionService {

    public enum Action { WARNING, SUSPEND, LIFT, KICK }

    /** 정지 기간 3일·14일·30일·영구 (D-57). */
    public enum Duration {
        D3(3, "3일"), D14(14, "14일"), D30(30, "30일"), FOREVER(0, "영구");

        final int days;
        public final String label;

        Duration(int days, String label) {
            this.days = days;
            this.label = label;
        }
    }

    static final LocalDateTime FOREVER = LocalDateTime.of(9999, 12, 31, 0, 0);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    static final int REASON_MAX = 200;
    static final int APPEAL_MAX = 500;

    private final JdbcTemplate jdbc;
    private final NotificationWriter notifications;
    private final AppProperties props;
    private final Clock clock;

    public SanctionService(JdbcTemplate jdbc, NotificationWriter notifications, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.props = props;
        this.clock = clock;
    }

    // ----- 멤버 목록 (관리 화면) -----

    /** 멤버 관리 화면. 이메일·전화번호는 가리고, 최근 1년 경고·정지 횟수를 같이 보인다 (4.4, D-87). */
    @Transactional(readOnly = true)
    public List<MemberRow> members(String slug) {
        long blogId = blogId(slug);
        LocalDateTime now = LocalDateTime.now(clock);
        return jdbc.query("""
                SELECT m.id, m.nickname, m.name, m.email, m.phone, bm.role, bm.joined_at, bm.suspended_until,
                       bm.perm_edit_info, bm.perm_manage_members, bm.perm_manage_posts,
                       (SELECT COUNT(*) FROM member_sanctions s WHERE s.blog_id = bm.blog_id AND s.member_id = m.id
                          AND s.type = 'WARNING' AND s.created_at >= ?) AS warnings,
                       (SELECT COUNT(*) FROM member_sanctions s WHERE s.blog_id = bm.blog_id AND s.member_id = m.id
                          AND s.type = 'SUSPEND' AND s.created_at >= ?) AS suspensions
                FROM blog_memberships bm JOIN members m ON m.id = bm.member_id
                WHERE bm.blog_id = ? AND bm.status = 'ACTIVE'
                ORDER BY FIELD(bm.role, 'OWNER', 'SUB_OWNER', 'MEMBER'), bm.joined_at
                """, (rs, i) -> {
                    LocalDateTime until = rs.getObject("suspended_until", LocalDateTime.class);
                    boolean suspended = until != null && until.isAfter(now);
                    return new MemberRow(rs.getLong("id"), rs.getString("nickname"), rs.getString("name"),
                            Masking.email(rs.getString("email")), Masking.phone(rs.getString("phone")),
                            rs.getString("role"), rs.getObject("joined_at", LocalDateTime.class).format(DATE),
                            new Permissions(rs.getBoolean("perm_edit_info"), rs.getBoolean("perm_manage_members"),
                                    rs.getBoolean("perm_manage_posts")),
                            suspended ? untilText(until) : null, rs.getInt("warnings"), rs.getInt("suspensions"),
                            rs.getInt("suspensions") >= 3);
                }, now.minusYears(1), now.minusYears(1), blogId);
    }

    // ----- 부블로그장 (D-03, D-71) -----

    /** 블로그장만. 부블로그장으로 올리면서 권한을 고르거나, 일반 멤버로 내린다. 부블로그장이 된 시각은 자동 위임 순서다 (D-17). */
    @Transactional
    public void setRole(String slug, long ownerId, long memberId, BlogMembership.Role role, Permissions perms) {
        long blogId = blogId(slug);
        if (memberId == ownerId) {
            throw ApiException.badRequest("SELF_ROLE", "블로그장 자신의 역할은 바꿀 수 없습니다. 블로그장을 넘기려면 위임을 쓰세요.");
        }
        Target t = target(blogId, memberId);
        if (t.role() == BlogMembership.Role.OWNER) {
            throw ApiException.badRequest("OWNER_ROLE", "블로그장의 역할은 바꿀 수 없습니다.");
        }
        if (role == BlogMembership.Role.SUB_OWNER) {
            Permissions p = perms == null ? new Permissions(false, false, false) : perms;
            jdbc.update("""
                    UPDATE blog_memberships SET role = 'SUB_OWNER', perm_edit_info = ?, perm_manage_members = ?,
                           perm_manage_posts = ?, sub_owner_since = COALESCE(sub_owner_since, ?)
                    WHERE blog_id = ? AND member_id = ?
                    """, p.editInfo(), p.manageMembers(), p.managePosts(), LocalDateTime.now(clock), blogId, memberId);
        } else if (role == BlogMembership.Role.MEMBER) {
            jdbc.update("""
                    UPDATE blog_memberships SET role = 'MEMBER', perm_edit_info = FALSE, perm_manage_members = FALSE,
                           perm_manage_posts = FALSE, sub_owner_since = NULL
                    WHERE blog_id = ? AND member_id = ?
                    """, blogId, memberId);
        } else {
            throw ApiException.badRequest("INVALID_ROLE", "부블로그장 또는 멤버만 고를 수 있습니다.");
        }
    }

    // ----- 경고·정지·해제·강제 퇴장 (BLG-13) -----

    /**
     * 블로그장 본인은 대상이 될 수 없다(메인 관리자 몫, D-85). 부블로그장끼리는 블로그장만 제재한다.
     * 신고에서 시작했으면 reportId를 남긴다.
     */
    @Transactional
    public Result sanction(String slug, long actorId, long memberId, Action action, Duration duration, String rawReason,
            Long reportId) {
        long blogId = blogId(slug);
        if (memberId == actorId) {
            throw ApiException.badRequest("SELF_SANCTION", "자기 자신은 제재할 수 없습니다.");
        }
        Target t = target(blogId, memberId);
        if (t.role() == BlogMembership.Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_TARGET", "블로그장은 메인 관리자만 제재할 수 있습니다.");
        }
        BlogMembership.Role actorRole = target(blogId, actorId).role();
        if (t.role() == BlogMembership.Role.SUB_OWNER && actorRole != BlogMembership.Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUB_OWNER_TARGET", "부블로그장은 블로그장만 제재할 수 있습니다.");
        }
        String reason = reason(rawReason);
        LocalDateTime now = LocalDateTime.now(clock);
        if (action == null) {
            throw ApiException.badRequest("INVALID_ACTION", "조치를 골라 주세요.");
        }
        switch (action) {
            case WARNING -> notifications.blog(memberId, Type.MEMBER_WARNED, blogId, actorId, null);
            case SUSPEND -> {
                if (duration == null) {
                    throw ApiException.badRequest("INVALID_DURATION", "정지 기간을 골라 주세요.");
                }
                LocalDateTime until = duration == Duration.FOREVER ? FOREVER : now.plusDays(duration.days);
                jdbc.update("UPDATE blog_memberships SET suspended_until = ? WHERE blog_id = ? AND member_id = ?",
                        until, blogId, memberId);
                notifications.blog(memberId, Type.MEMBER_SUSPENDED, blogId, actorId,
                        duration == Duration.FOREVER ? "영구" : untilText(until) + "까지");
            }
            case LIFT -> {
                if (t.suspendedUntil() == null || !t.suspendedUntil().isAfter(now)) {
                    throw ApiException.badRequest("NOT_SUSPENDED", "정지 중인 멤버가 아닙니다.");
                }
                jdbc.update("UPDATE blog_memberships SET suspended_until = NULL WHERE blog_id = ? AND member_id = ?",
                        blogId, memberId);
                notifications.blog(memberId, Type.MEMBER_UNSUSPENDED, blogId, actorId, null);
            }
            case KICK -> kick(blogId, memberId, actorId, now);
        }
        jdbc.update("""
                INSERT INTO member_sanctions (blog_id, member_id, actor_id, report_id, type, duration, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, blogId, memberId, actorId, reportId, action.name(),
                action == Action.SUSPEND ? duration.name() : null, reason, now);
        int suspensions = count("""
                SELECT COUNT(*) FROM member_sanctions WHERE blog_id = ? AND member_id = ? AND type = 'SUSPEND'
                  AND created_at >= ?""", blogId, memberId, now.minusYears(1));
        return new Result(action.name(), suspensions, action != Action.KICK && suspensions >= 3);
    }

    /**
     * 강제 퇴장: 멤버에서 빼고 글은 "탈퇴한 계정"으로, 이름·이메일·전화번호는 해시로만 블랙리스트에 남긴다 (BLG-11, D-33, D-34).
     * 다시 들어오려면 해제 문의(BLG-12)를 거쳐야 한다.
     */
    private void kick(long blogId, long memberId, long actorId, LocalDateTime now) {
        jdbc.update("""
                UPDATE blog_memberships SET status = 'KICKED', role = 'MEMBER', left_at = ?, suspended_until = NULL,
                       perm_edit_info = FALSE, perm_manage_members = FALSE, perm_manage_posts = FALSE, sub_owner_since = NULL
                WHERE blog_id = ? AND member_id = ?
                """, now, blogId, memberId);
        jdbc.update("UPDATE posts SET author_left = TRUE WHERE blog_id = ? AND author_id = ?", blogId, memberId);
        jdbc.query("SELECT name, email, phone FROM members WHERE id = ?", rs -> {
            jdbc.update("""
                    INSERT INTO blacklist_entries (blog_id, member_id, name_hash, email_hash, phone_hash, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, blogId, memberId, hash(normalizeName(rs.getString("name"))),
                    hash(normalizeEmail(rs.getString("email"))), hash(normalizePhone(rs.getString("phone"))), now);
        }, memberId);
        notifications.blog(memberId, Type.MEMBER_KICKED, blogId, actorId, null);
    }

    /** 이 블로그에서 받은 경고·정지·강제 퇴장 기록. 최근 1년 (D-60). */
    @Transactional(readOnly = true)
    public List<SanctionRow> history(String slug) {
        long blogId = blogId(slug);
        return jdbc.query("""
                SELECT s.id, s.type, s.duration, s.reason, s.created_at, s.report_id, t.nickname AS target, a.nickname AS actor
                FROM member_sanctions s JOIN members t ON t.id = s.member_id JOIN members a ON a.id = s.actor_id
                WHERE s.blog_id = ? AND s.created_at >= ? ORDER BY s.created_at DESC, s.id DESC LIMIT 100
                """, (rs, i) -> new SanctionRow(rs.getLong("id"), rs.getString("type"),
                rs.getString("duration") == null ? null : Duration.valueOf(rs.getString("duration")).label,
                rs.getString("reason"), rs.getObject("created_at", LocalDateTime.class).format(DATE_TIME),
                rs.getString("target"), rs.getString("actor"), rs.getObject("report_id", Long.class) != null),
                blogId, LocalDateTime.now(clock).minusYears(1));
    }

    // ----- 블랙리스트 (BLG-11, BLG-12) -----

    /** 이 블로그에 참여할 수 없게 막혀 있으면 문의할 수 있도록 블랙리스트 번호를 실어 거절한다. */
    @Transactional(readOnly = true)
    public void requireNotBlacklisted(long blogId, long memberId) {
        findEntry(blogId, memberId).ifPresent(entryId -> {
            throw new DetailedApiException(HttpStatus.FORBIDDEN, "JOIN_BLACKLISTED",
                    "이 블로그의 블랙리스트에 올라 있어 참여할 수 없습니다. 잘못 막혔다면 해제 문의를 남겨 주세요.",
                    Map.of("canAppeal", true));
        });
    }

    /** 이메일이나 전화번호가 그 블로그의 (풀리지 않은) 블랙리스트와 같은지. 탈퇴 뒤 다시 가입해도 막힌다 (BLG-11). */
    private Optional<Long> findEntry(long blogId, long memberId) {
        List<Map<String, Object>> me = jdbc.queryForList("SELECT email, phone FROM members WHERE id = ?", memberId);
        if (me.isEmpty()) {
            return Optional.empty();
        }
        String email = hash(normalizeEmail((String) me.get(0).get("email")));
        String phone = hash(normalizePhone((String) me.get(0).get("phone")));
        return jdbc.queryForList("""
                SELECT id FROM blacklist_entries WHERE blog_id = ? AND lifted_at IS NULL
                  AND (email_hash = ? OR (phone_hash = ? AND phone_hash <> ?)) ORDER BY id DESC LIMIT 1
                """, Long.class, blogId, email, phone, hash("")).stream().findFirst();
    }

    /** 블랙리스트 목록. 블로그장이 바뀌어도 열람만 된다 (D-55). 원문 개인정보는 없고 닉네임만 보인다. */
    @Transactional(readOnly = true)
    public List<BlacklistRow> blacklist(String slug) {
        long blogId = blogId(slug);
        return jdbc.query("""
                SELECT e.id, e.created_at, e.lifted_at, m.nickname, m.status
                FROM blacklist_entries e LEFT JOIN members m ON m.id = e.member_id
                WHERE e.blog_id = ? ORDER BY e.id DESC
                """, (rs, i) -> new BlacklistRow(rs.getLong("id"),
                rs.getString("nickname") == null || !"ACTIVE".equals(rs.getString("status")) ? "탈퇴한 회원"
                        : rs.getString("nickname"),
                rs.getObject("created_at", LocalDateTime.class).format(DATE),
                rs.getObject("lifted_at", LocalDateTime.class) == null ? null
                        : rs.getObject("lifted_at", LocalDateTime.class).format(DATE)), blogId);
    }

    /**
     * 해제 문의 (BLG-12). 전화번호 주인이 바뀌어 잘못 막힌 사람이 남긴다. 서버가 이름·전화번호를 해시로 비교한 결과만 블로그장에게 보인다.
     * 기다리는 문의가 있으면 다시 남길 수 없다.
     */
    @Transactional
    public void appeal(Blog blog, long memberId, String rawMessage) {
        long entryId = findEntry(blog.getId(), memberId)
                .orElseThrow(() -> ApiException.badRequest("NOT_BLACKLISTED", "블랙리스트에 올라 있지 않습니다."));
        if (count("SELECT COUNT(*) FROM blacklist_appeals WHERE blog_id = ? AND member_id = ? AND status = 'PENDING'",
                blog.getId(), memberId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_APPEALED", "이미 해제 문의를 남겼습니다. 블로그장의 답을 기다려 주세요.");
        }
        String message = rawMessage == null ? "" : rawMessage.strip();
        if (message.codePointCount(0, message.length()) > APPEAL_MAX) {
            throw ApiException.badRequest("INVALID_MESSAGE", "문의 내용은 %d자까지 쓸 수 있습니다.".formatted(APPEAL_MAX));
        }
        Map<String, Object> me = jdbc.queryForMap("SELECT name, phone FROM members WHERE id = ?", memberId);
        Map<String, Object> entry = jdbc.queryForMap("SELECT name_hash, phone_hash FROM blacklist_entries WHERE id = ?",
                entryId);
        boolean nameMatched = hash(normalizeName((String) me.get("name"))).equals(entry.get("name_hash"));
        boolean phoneMatched = hash(normalizePhone((String) me.get("phone"))).equals(entry.get("phone_hash"));
        jdbc.update("""
                INSERT INTO blacklist_appeals (blog_id, entry_id, member_id, message, name_matched, phone_matched, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, blog.getId(), entryId, memberId, message.isEmpty() ? null : message, nameMatched, phoneMatched,
                LocalDateTime.now(clock));
        jdbc.queryForList("""
                SELECT member_id FROM blog_memberships WHERE blog_id = ? AND status = 'ACTIVE'
                  AND (role = 'OWNER' OR (role = 'SUB_OWNER' AND perm_manage_members = TRUE))
                """, Long.class, blog.getId())
                .forEach(id -> notifications.blog(id, Type.BLACKLIST_APPEAL, blog.getId(), memberId, null));
    }

    @Transactional(readOnly = true)
    public List<AppealRow> appeals(String slug) {
        long blogId = blogId(slug);
        return jdbc.query("""
                SELECT a.id, a.message, a.name_matched, a.phone_matched, a.status, a.created_at, m.nickname
                FROM blacklist_appeals a JOIN members m ON m.id = a.member_id
                WHERE a.blog_id = ? ORDER BY a.status = 'PENDING' DESC, a.id DESC LIMIT 50
                """, (rs, i) -> new AppealRow(rs.getLong("id"), rs.getString("nickname"), rs.getString("message"),
                rs.getBoolean("name_matched"), rs.getBoolean("phone_matched"), rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class).format(DATE_TIME)), blogId);
    }

    /** 블로그장(멤버 관리 권한이 있는 부블로그장 포함)이 해제하거나 거절한다. 해제하면 다시 참여 신청할 수 있다. */
    @Transactional
    public void decideAppeal(String slug, long appealId, long actorId, boolean lift) {
        long blogId = blogId(slug);
        Map<String, Object> a = jdbc.queryForList("""
                SELECT entry_id, member_id, status FROM blacklist_appeals WHERE id = ? AND blog_id = ? FOR UPDATE
                """, appealId, blogId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPEAL_NOT_FOUND", "문의를 찾을 수 없습니다."));
        if (!"PENDING".equals(a.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PROCESSED", "이미 처리한 문의입니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long memberId = ((Number) a.get("member_id")).longValue();
        jdbc.update("UPDATE blacklist_appeals SET status = ?, processed_by = ?, processed_at = ? WHERE id = ?",
                lift ? "LIFTED" : "REJECTED", actorId, now, appealId);
        if (lift) {
            jdbc.update("UPDATE blacklist_entries SET lifted_at = ? WHERE id = ?", now, a.get("entry_id"));
            // 강제 퇴장 표시도 풀어 다시 신청할 수 있게 한다
            jdbc.update("UPDATE blog_memberships SET status = 'LEFT' WHERE blog_id = ? AND member_id = ? AND status = 'KICKED'",
                    blogId, memberId);
        }
        notifications.blog(memberId, lift ? Type.BLACKLIST_LIFTED : Type.BLACKLIST_REJECTED, blogId, actorId, null);
    }

    // ----- 정지 안내 -----

    /** 정지된 멤버에게 보일 마지막 정지 사유. */
    @Transactional(readOnly = true)
    public Optional<String> lastSuspendReason(long blogId, long memberId) {
        return jdbc.queryForList("""
                SELECT reason FROM member_sanctions WHERE blog_id = ? AND member_id = ? AND type = 'SUSPEND'
                ORDER BY id DESC LIMIT 1
                """, String.class, blogId, memberId).stream().filter(r -> r != null && !r.isBlank()).findFirst();
    }

    // ----- 공통 -----

    private long blogId(String slug) {
        return jdbc.queryForList("SELECT id FROM blogs WHERE slug = ? AND status <> 'CLOSED'", Long.class, slug)
                .stream().findFirst().orElseThrow(BlogService::notFound);
    }

    private Target target(long blogId, long memberId) {
        return jdbc.query("""
                SELECT role, suspended_until FROM blog_memberships WHERE blog_id = ? AND member_id = ? AND status = 'ACTIVE'
                """, (rs, i) -> new Target(BlogMembership.Role.valueOf(rs.getString("role")),
                rs.getObject("suspended_until", LocalDateTime.class)), blogId, memberId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "이 블로그의 멤버가 아닙니다."));
    }

    static String reason(String raw) {
        String r = raw == null ? "" : raw.strip();
        if (r.codePointCount(0, r.length()) > REASON_MAX) {
            throw ApiException.badRequest("INVALID_REASON", "사유는 %d자까지 쓸 수 있습니다.".formatted(REASON_MAX));
        }
        return r.isEmpty() ? null : r;
    }

    static String untilText(LocalDateTime until) {
        return until.getYear() >= 9999 ? "영구" : until.format(DATE_TIME);
    }

    private String hash(String value) {
        return Hashing.hmacSha256Hex(props.auth().codeHashSecret(), "blacklist:" + value);
    }

    private static String normalizeName(String v) {
        return v == null ? "" : v.replaceAll("\\s+", "");
    }

    private static String normalizeEmail(String v) {
        return v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizePhone(String v) {
        return v == null ? "" : v.replaceAll("\\D", "");
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private record Target(BlogMembership.Role role, LocalDateTime suspendedUntil) {
    }

    public record Permissions(boolean editInfo, boolean manageMembers, boolean managePosts) {
    }

    /** suspendedUntil: 정지 중일 때만. kickSuggested: 최근 1년 정지 3번이면 블로그장 화면에 표시 (D-46). */
    public record MemberRow(long id, String nickname, String name, String email, String phone, String role,
            String joinedAt, Permissions permissions, String suspendedUntil, int warnings, int suspensions,
            boolean kickSuggested) {
    }

    public record Result(String action, int suspensions, boolean kickSuggested) {
    }

    public record SanctionRow(long id, String type, String duration, String reason, String createdAt, String target,
            String actor, boolean fromReport) {
    }

    public record BlacklistRow(long id, String nickname, String createdAt, String liftedAt) {
    }

    public record AppealRow(long id, String nickname, String message, boolean nameMatched, boolean phoneMatched,
            String status, String createdAt) {
    }
}
