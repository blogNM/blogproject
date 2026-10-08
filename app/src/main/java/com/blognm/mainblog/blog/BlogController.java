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

import org.springframework.web.multipart.MultipartFile;

import com.blognm.mainblog.auth.MemberPrincipal;
import com.blognm.mainblog.image.Image;
import com.blognm.mainblog.image.ImageService;

/**
 * 블로그 API (002). 블로그 안의 관리 기능은 @PreAuthorize로 블로그별 역할을 매 요청 확인한다 (헌법 II, SEC-07).
 * 목록·블로그 정보 조회만 비회원도 쓸 수 있다 (SecurityConfig).
 */
@RestController
public class BlogController {

    private final BlogService blogService;
    private final BlogQueryService queryService;
    private final ImageService imageService;

    public BlogController(BlogService blogService, BlogQueryService queryService, ImageService imageService) {
        this.blogService = blogService;
        this.queryService = queryService;
        this.imageService = imageService;
    }

    @GetMapping("/api/blogs")
    public BlogQueryService.Page list(@RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page) {
        return queryService.list(sort, q, page);
    }

    @PostMapping("/api/blogs")
    public Map<String, String> create(@AuthenticationPrincipal MemberPrincipal me,
            @RequestBody BlogService.CreateRequest body) {
        return Map.of("slug", blogService.create(me.memberId(), body));
    }

    @GetMapping("/api/blog-slug-available")
    public Map<String, Boolean> slugAvailable(@RequestParam String slug) {
        return Map.of("available", blogService.isSlugAvailable(slug));
    }

    @GetMapping("/api/blogs/{slug}")
    public BlogService.BlogView view(@PathVariable String slug, @RequestParam(required = false) String key,
            @AuthenticationPrincipal MemberPrincipal me) {
        return blogService.view(slug, key, me == null ? null : me.memberId());
    }

    @PutMapping("/api/blogs/{slug}")
    @PreAuthorize("@blogAuth.canEditInfo(#slug)")
    public Map<String, Boolean> update(@PathVariable String slug, @AuthenticationPrincipal MemberPrincipal me,
            @RequestBody BlogService.UpdateRequest body) {
        blogService.update(slug, me.memberId(), body);
        return Map.of("updated", true);
    }

    @PutMapping("/api/blogs/{slug}/theme")
    @PreAuthorize("@blogAuth.canEditInfo(#slug)")
    public Map<String, Boolean> updateTheme(@PathVariable String slug, @RequestBody BlogService.ThemeRequest body) {
        blogService.updateTheme(slug, body);
        return Map.of("updated", true);
    }

    /** 머리 사진 올리기. 사진 검사는 글 사진과 같다 (SEC-08). */
    @PostMapping("/api/blogs/{slug}/cover")
    @PreAuthorize("@blogAuth.canEditInfo(#slug)")
    public Map<String, String> uploadCover(@PathVariable String slug, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal MemberPrincipal me) {
        Image image = imageService.upload(me.memberId(), file);
        return Map.of("coverUrl", blogService.changeCover(slug, image.getId()));
    }

    @DeleteMapping("/api/blogs/{slug}/cover")
    @PreAuthorize("@blogAuth.canEditInfo(#slug)")
    public Map<String, Boolean> removeCover(@PathVariable String slug) {
        blogService.changeCover(slug, null);
        return Map.of("removed", true);
    }

    @PostMapping("/api/blogs/{slug}/share-link")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, String> regenerateShareLink(@PathVariable String slug) {
        return Map.of("shareToken", blogService.regenerateShareLink(slug));
    }

    // ----- 참여 (BLG-04, BLG-05, BLG-07) -----

    @PostMapping("/api/blogs/{slug}/join")
    public Map<String, Boolean> join(@PathVariable String slug, @RequestBody(required = false) JoinBody body,
            @AuthenticationPrincipal MemberPrincipal me) {
        boolean joined = blogService.join(slug, body == null ? null : body.key(), me.memberId());
        return Map.of("joined", joined, "requested", !joined);
    }

    @DeleteMapping("/api/blogs/{slug}/join")
    public Map<String, Boolean> cancelJoin(@PathVariable String slug, @AuthenticationPrincipal MemberPrincipal me) {
        blogService.cancelRequest(slug, me.memberId());
        return Map.of("cancelled", true);
    }

    @PostMapping("/api/blogs/{slug}/leave")
    public Map<String, Boolean> leave(@PathVariable String slug, @AuthenticationPrincipal MemberPrincipal me) {
        blogService.leave(slug, me.memberId());
        return Map.of("left", true);
    }

    @GetMapping("/api/blogs/{slug}/join-requests")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public List<BlogService.PendingRequest> joinRequests(@PathVariable String slug) {
        return blogService.pendingRequests(slug);
    }

    @PostMapping("/api/blogs/{slug}/join-requests/{requestId}/approve")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public Map<String, Boolean> approve(@PathVariable String slug, @PathVariable long requestId,
            @AuthenticationPrincipal MemberPrincipal me) {
        blogService.decide(slug, requestId, me.memberId(), true);
        return Map.of("approved", true);
    }

    @PostMapping("/api/blogs/{slug}/join-requests/{requestId}/reject")
    @PreAuthorize("@blogAuth.canManageMembers(#slug)")
    public Map<String, Boolean> reject(@PathVariable String slug, @PathVariable long requestId,
            @AuthenticationPrincipal MemberPrincipal me) {
        blogService.decide(slug, requestId, me.memberId(), false);
        return Map.of("rejected", true);
    }

    @GetMapping("/api/me/blogs")
    public BlogQueryService.MyBlogs myBlogs(@AuthenticationPrincipal MemberPrincipal me) {
        return queryService.myBlogs(me.memberId());
    }

    public record JoinBody(String key) {
    }
}
