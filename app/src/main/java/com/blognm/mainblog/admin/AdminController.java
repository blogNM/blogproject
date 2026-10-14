package com.blognm.mainblog.admin;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.auth.AdminPrincipal;
import com.blognm.mainblog.auth.AuthCookies;
import com.blognm.mainblog.blog.BlogLifecycleService;
import com.blognm.mainblog.blog.SanctionReason;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.common.ClientIp;
import com.blognm.mainblog.report.ReportReviewService;

/** 메인 관리자 페이지의 API (ADM-01~07, SEC-09). /api/admin/** 은 관리자 로그인만 들어온다 (SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminAccountService accounts;
    private final AdminService admin;
    private final BlogLifecycleService lifecycle;
    private final ReportReviewService reports;
    private final AdminLog adminLog;
    private final AuthCookies cookies;

    public AdminController(AdminAccountService accounts, AdminService admin, BlogLifecycleService lifecycle,
            ReportReviewService reports, AdminLog adminLog, AuthCookies cookies) {
        this.accounts = accounts;
        this.admin = admin;
        this.lifecycle = lifecycle;
        this.reports = reports;
        this.adminLog = adminLog;
        this.cookies = cookies;
    }

    // ----- 로그인 -----

    @PostMapping("/auth/login")
    public Map<String, Boolean> login(@RequestBody LoginBody body, HttpServletRequest request, HttpServletResponse response) {
        AdminAccountService.Login login = accounts.login(body.loginId(), body.password(), ClientIp.of(request));
        cookies.writeAdmin(response, login.token());
        return Map.of("mustChangePassword", login.mustChangePassword());
    }

    @PostMapping("/auth/logout")
    public Map<String, Boolean> logout(HttpServletResponse response) {
        cookies.clearAdmin(response);
        return Map.of("loggedOut", true);
    }

    @GetMapping("/auth/me")
    public AdminAccountService.Me me(@AuthenticationPrincipal AdminPrincipal me) {
        return accounts.me(me.adminId());
    }

    @PostMapping("/auth/password")
    public Map<String, Boolean> password(@RequestBody PasswordBody body, @AuthenticationPrincipal AdminPrincipal me,
            HttpServletResponse response) {
        cookies.writeAdmin(response, accounts.changePassword(me.adminId(), body.currentPassword(), body.newPassword()));
        return Map.of("changed", true);
    }

    // ----- 관리자 계정 -----

    @GetMapping("/admins")
    public List<AdminAccountService.AdminRow> admins() {
        return accounts.list();
    }

    @PostMapping("/admins")
    public Map<String, Long> createAdmin(@RequestBody CreateAdminBody body, @AuthenticationPrincipal AdminPrincipal me) {
        return Map.of("id", accounts.create(me.adminId(), body.loginId(), body.name(), body.tempPassword()));
    }

    // ----- 회원 (ADM-01) -----

    @GetMapping("/members")
    public AdminService.MemberPage members(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String kind, @RequestParam(defaultValue = "1") int page) {
        AdminService.MemberKind k;
        try {
            k = AdminService.MemberKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw com.blognm.mainblog.common.ApiException.badRequest("INVALID_INPUT", "회원 종류를 확인해 주세요.");
        }
        return admin.members(q, k, page);
    }

    @GetMapping("/members/{id}")
    public AdminService.MemberDetail member(@PathVariable long id) {
        return admin.member(id);
    }

    @PostMapping("/members/{id}/reveal")
    public AdminService.Pii reveal(@PathVariable long id, @AuthenticationPrincipal AdminPrincipal me) {
        return admin.revealMember(me.adminId(), id);
    }

    // ----- 블로그 (ADM-02, ADM-07) -----

    @GetMapping("/blogs")
    public AdminService.Page<AdminService.BlogItem> blogs(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page) {
        return admin.blogs(q, page);
    }

    @PostMapping("/blogs/{id}/{action}")
    @Transactional
    public Map<String, Object> blogAction(@PathVariable long id, @PathVariable String action,
            @RequestBody(required = false) ReasonBody body, @AuthenticationPrincipal AdminPrincipal me) {
        String reason = body == null ? null : body.reason();
        long adminId = me.adminId();
        return switch (action) {
            case "hide" -> {
                lifecycle.setHidden(id, true);
                adminLog.write(adminId, AdminLog.Action.HIDE, "BLOG", id, reason);
                yield Map.of("hidden", true);
            }
            case "unhide" -> {
                lifecycle.setHidden(id, false);
                adminLog.write(adminId, AdminLog.Action.UNHIDE, "BLOG", id, reason);
                yield Map.of("hidden", false);
            }
            case "close" -> {
                String closeAt = lifecycle.closeByAdmin(id);
                adminLog.write(adminId, AdminLog.Action.CLOSE, "BLOG", id, reason);
                yield Map.of("closeAt", closeAt);
            }
            case "warn-owner" -> {
                BlogLifecycleService.OwnerWarning w = lifecycle.warnOwner(id, adminId, body == null ? null : body.reasonCode(),
                        reason, null);
                adminLog.write(adminId, AdminLog.Action.WARN, "BLOG", id, reason);
                if (w.kicked()) {
                    adminLog.write(adminId, AdminLog.Action.KICK, "BLOG", id, "경고 " + w.count() + "번");
                }
                yield Map.of("warnings", w.count(), "kicked", w.kicked());
            }
            case "kick-owner" -> {
                String result = lifecycle.kickOwner(id);
                adminLog.write(adminId, AdminLog.Action.KICK, "BLOG", id, reason);
                yield Map.of("result", result);
            }
            default -> throw ApiException.badRequest("INVALID_ACTION", "할 수 없는 조치입니다.");
        };
    }

    // ----- 글·댓글 (ADM-03) -----

    @GetMapping("/posts")
    public AdminService.Page<AdminService.PostItem> posts(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page) {
        return admin.posts(q, status(status), page);
    }

    @PostMapping("/posts/{id}/{action}")
    public Map<String, Boolean> postAction(@PathVariable long id, @PathVariable String action,
            @RequestBody(required = false) ReasonBody body, @AuthenticationPrincipal AdminPrincipal me) {
        admin.post(me.adminId(), id, contentAction(action), body == null ? null : body.reason());
        return Map.of("done", true);
    }

    @GetMapping("/comments")
    public AdminService.Page<AdminService.CommentItem> comments(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page) {
        return admin.comments(q, status(status), page);
    }

    @PostMapping("/comments/{id}/{action}")
    public Map<String, Boolean> commentAction(@PathVariable long id, @PathVariable String action,
            @RequestBody(required = false) ReasonBody body, @AuthenticationPrincipal AdminPrincipal me) {
        admin.comment(me.adminId(), id, contentAction(action), body == null ? null : body.reason());
        return Map.of("done", true);
    }

    // ----- 신고 (ADM-04) -----

    @GetMapping("/reports")
    public ReportReviewService.Page reports(@RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(required = false) SanctionReason reason, @RequestParam(defaultValue = "1") int page) {
        return reports.forAdmin(!"DONE".equals(status), reason, page);
    }

    @PostMapping("/reports/{id}")
    @Transactional
    public Map<String, String> processReport(@PathVariable long id, @RequestBody ReportBody body,
            @AuthenticationPrincipal AdminPrincipal me) {
        ReportReviewService.AdminResult result;
        try {
            result = ReportReviewService.AdminResult.valueOf(body.result());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_RESULT", "처리 결과를 골라 주세요.");
        }
        String done = reports.processByAdmin(id, me.adminId(), result, body.sanctionReason(), body.reason());
        adminLog.write(me.adminId(), AdminLog.Action.REPORT, "REPORT", id, result.name()
                + (body.reason() == null || body.reason().isBlank() ? "" : ": " + body.reason().strip()));
        return Map.of("result", done);
    }

    // ----- 통계·기록 (ADM-05, ADM-06) -----

    @GetMapping("/stats")
    public AdminService.Stats stats() {
        return admin.stats();
    }

    @GetMapping("/logs")
    public AdminService.Page<AdminService.LogItem> logs(@RequestParam(defaultValue = "1") int page) {
        return admin.logs(page);
    }

    private static AdminService.ContentAction contentAction(String action) {
        return switch (action) {
            case "hide" -> AdminService.ContentAction.HIDE;
            case "unhide" -> AdminService.ContentAction.UNHIDE;
            case "delete" -> AdminService.ContentAction.DELETE;
            case "restore" -> AdminService.ContentAction.RESTORE;
            default -> throw ApiException.badRequest("INVALID_ACTION", "할 수 없는 조치입니다.");
        };
    }

    private static String status(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        if (!List.of("NORMAL", "HIDDEN", "DELETED").contains(s)) {
            throw ApiException.badRequest("INVALID_STATUS", "상태 값이 맞지 않습니다.");
        }
        return s;
    }

    public record LoginBody(String loginId, String password) {
    }

    public record PasswordBody(String currentPassword, String newPassword) {
    }

    public record CreateAdminBody(String loginId, String name, String tempPassword) {
    }

    public record ReasonBody(String reason, SanctionReason reasonCode) {
    }

    public record ReportBody(String result, SanctionReason sanctionReason, String reason) {
    }
}
