package com.blognm.mainblog.blog;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.auth.MemberPrincipal;
import com.blognm.mainblog.report.ReportReviewService;

/**
 * 블로그 관리 화면의 멤버 관리·제재·블랙리스트·신고 처리·위임·폐쇄 (002 US4·US5, 6단계).
 * 권한은 매 요청 DB에서 다시 본다 (@blogAuth, SEC-07).
 */
@RestController
public class BlogModerationController {

    private final SanctionService sanctions;
    private final BlogLifecycleService lifecycle;
    private final BlogService blogs;
    private final ReportReviewService reports;

    public BlogModerationController(SanctionService sanctions, BlogLifecycleService lifecycle, BlogService blogs,
            ReportReviewService reports) {
        this.sanctions = sanctions;
        this.lifecycle = lifecycle;
        this.blogs = blogs;
        this.reports = reports;
    }

    // ----- 멤버 관리 -----

    @GetMapping("/api/blogs/{slug}/members")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public List<SanctionService.MemberRow> members(@PathVariable String slug) {
        return sanctions.members(slug);
    }

    /** 부블로그장 지정·권한 체크와 해제. 블로그장만 (D-71). */
    @PutMapping("/api/blogs/{slug}/members/{memberId}/role")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, Boolean> role(@PathVariable String slug, @PathVariable long memberId, @RequestBody RoleBody body,
            @AuthenticationPrincipal MemberPrincipal me) {
        sanctions.setRole(slug, me.memberId(), memberId, body.role(), body.permissions());
        return Map.of("updated", true);
    }

    @PostMapping("/api/blogs/{slug}/members/{memberId}/sanctions")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public SanctionService.Result sanction(@PathVariable String slug, @PathVariable long memberId,
            @RequestBody SanctionBody body, @AuthenticationPrincipal MemberPrincipal me) {
        return sanctions.sanction(slug, me.memberId(), memberId, body.action(), body.duration(), body.reasonCode(),
                body.reason(), null);
    }

    @GetMapping("/api/blogs/{slug}/sanctions")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public List<SanctionService.SanctionRow> sanctionHistory(@PathVariable String slug,
            @RequestParam(required = false) SanctionReason reason) {
        return sanctions.history(slug, reason);
    }

    // ----- 블랙리스트 (BLG-11, BLG-12) -----

    @GetMapping("/api/blogs/{slug}/blacklist")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public List<SanctionService.BlacklistRow> blacklist(@PathVariable String slug) {
        return sanctions.blacklist(slug);
    }

    @GetMapping("/api/blogs/{slug}/blacklist-appeals")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public List<SanctionService.AppealRow> appeals(@PathVariable String slug) {
        return sanctions.appeals(slug);
    }

    @PostMapping("/api/blogs/{slug}/blacklist-appeals/{appealId}/{decision:lift|reject}")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public Map<String, Boolean> decideAppeal(@PathVariable String slug, @PathVariable long appealId,
            @PathVariable String decision, @AuthenticationPrincipal MemberPrincipal me) {
        sanctions.decideAppeal(slug, appealId, me.memberId(), "lift".equals(decision));
        return Map.of("done", true);
    }

    /** 블랙리스트에 걸린 사람이 남기는 해제 문의. */
    @PostMapping("/api/blogs/{slug}/blacklist-appeals")
    public Map<String, Boolean> appeal(@PathVariable String slug, @RequestBody AppealBody body,
            @AuthenticationPrincipal MemberPrincipal me) {
        sanctions.appeal(blogs.visibleBlog(slug, body.key(), me.memberId()), me.memberId(), body.message());
        return Map.of("sent", true);
    }

    // ----- 블로그 안 신고 처리 (BLG-13) -----

    @GetMapping("/api/blogs/{slug}/reports")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public ReportReviewService.Page reports(@PathVariable String slug,
            @RequestParam(defaultValue = "PENDING") String status, @RequestParam(required = false) SanctionReason reason,
            @RequestParam(defaultValue = "1") int page) {
        return reports.forBlog(slug, !"DONE".equals(status), reason, page);
    }

    @PostMapping("/api/blogs/{slug}/reports/{reportId}")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public Map<String, Boolean> processReport(@PathVariable String slug, @PathVariable long reportId,
            @RequestBody ReportBody body, @AuthenticationPrincipal MemberPrincipal me) {
        reports.processByOwner(slug, reportId, me.memberId(), body.result(), body.duration(), body.sanctionReason(),
                body.reason());
        return Map.of("done", true);
    }

    // ----- 위임 (BLG-08) -----

    @PostMapping("/api/blogs/{slug}/transfer")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, Boolean> requestTransfer(@PathVariable String slug, @RequestBody TransferBody body,
            @AuthenticationPrincipal MemberPrincipal me) {
        lifecycle.requestTransfer(slug, me.memberId(), body.memberId());
        return Map.of("requested", true);
    }

    @DeleteMapping("/api/blogs/{slug}/transfer")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, Boolean> cancelTransfer(@PathVariable String slug) {
        lifecycle.cancelTransfer(slug);
        return Map.of("cancelled", true);
    }

    @PostMapping("/api/blogs/{slug}/transfer/{decision:accept|reject}")
    @PreAuthorize("@blogAuth.isMember(#slug)")
    public Map<String, Boolean> respondTransfer(@PathVariable String slug, @PathVariable String decision,
            @AuthenticationPrincipal MemberPrincipal me) {
        lifecycle.respondTransfer(slug, me.memberId(), "accept".equals(decision));
        return Map.of("done", true);
    }

    // ----- 폐쇄 (BLG-09) -----

    @PostMapping("/api/blogs/{slug}/close")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, String> close(@PathVariable String slug, @AuthenticationPrincipal MemberPrincipal me) {
        return Map.of("closeOn", lifecycle.close(slug, me.memberId()));
    }

    @DeleteMapping("/api/blogs/{slug}/close")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, Boolean> cancelClose(@PathVariable String slug, @AuthenticationPrincipal MemberPrincipal me) {
        lifecycle.cancelClose(slug, me.memberId());
        return Map.of("cancelled", true);
    }

    public record RoleBody(BlogMembership.Role role, SanctionService.Permissions permissions) {
    }

    public record SanctionBody(SanctionService.Action action, SanctionService.Duration duration,
            SanctionReason reasonCode, String reason) {
    }

    public record AppealBody(String key, String message) {
    }

    public record ReportBody(ReportReviewService.OwnerResult result, SanctionService.Duration duration,
            SanctionReason sanctionReason, String reason) {
    }

    public record TransferBody(long memberId) {
    }
}
