package com.blognm.mainblog.post;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

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
import com.blognm.mainblog.common.ClientIp;
import com.blognm.mainblog.linkpreview.LinkPreviewService;

/**
 * 글 API (004 US1~3). 목록·상세는 비회원도 읽는다(공개 범위 안에서, SecurityConfig).
 * 글 수정·삭제 권한은 글마다 달라 서비스에서 확인하고, 카테고리 관리는 @PreAuthorize로 확인한다.
 */
@RestController
public class PostController {

    private final PostService posts;
    private final PostQueryService query;
    private final CategoryService categories;
    private final ReactionService reactions;
    private final MarkdownRenderer markdown;
    private final LinkPreviewService linkPreviews;

    public PostController(PostService posts, PostQueryService query, CategoryService categories,
            ReactionService reactions, MarkdownRenderer markdown, LinkPreviewService linkPreviews) {
        this.markdown = markdown;
        this.linkPreviews = linkPreviews;
        this.posts = posts;
        this.query = query;
        this.categories = categories;
        this.reactions = reactions;
    }

    @GetMapping("/api/blogs/{slug}/posts")
    public PostQueryService.PostPage list(@PathVariable String slug, @RequestParam(required = false) String key,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long category, @RequestParam(required = false) String tag,
            @AuthenticationPrincipal MemberPrincipal me) {
        return query.list(slug, key, id(me), page, size, category, tag);
    }

    @PostMapping("/api/blogs/{slug}/posts")
    public Map<String, Long> write(@PathVariable String slug, @RequestBody PostService.WriteRequest body,
            @AuthenticationPrincipal MemberPrincipal me) {
        long id = posts.write(slug, me.memberId(), body);
        collectPreviews(body, me);
        return Map.of("id", id);
    }

    /** 글을 열면 조회수를 센다 (BRD-11). 볼 수 없는 글이면 세기 전에 404. */
    @GetMapping("/api/posts/{id}")
    public PostService.PostView view(@PathVariable long id, @RequestParam(required = false) String key,
            @AuthenticationPrincipal MemberPrincipal me, HttpServletRequest request) {
        posts.readable(id, key, id(me));
        reactions.recordView(id, id(me), ClientIp.of(request));
        return posts.view(id, key, id(me)).withLiked(reactions.liked(id, id(me)));
    }

    @PostMapping("/api/posts/{id}/like")
    public ReactionService.LikeResult like(@PathVariable long id, @RequestParam(required = false) String key,
            @AuthenticationPrincipal MemberPrincipal me) {
        return reactions.toggleLike(id, key, me.memberId());
    }

    @PutMapping("/api/posts/{id}")
    public Map<String, Boolean> update(@PathVariable long id, @RequestBody PostService.WriteRequest body,
            @AuthenticationPrincipal MemberPrincipal me) {
        posts.update(id, me.memberId(), body);
        collectPreviews(body, me);
        return Map.of("updated", true);
    }

    /** 글이 저장된 뒤(트랜잭션 밖에서) 링크 미리보기를 뒤에서 받아 둔다. 저장 응답은 기다리지 않는다 (D-92, D-124). */
    private void collectPreviews(PostService.WriteRequest body, MemberPrincipal me) {
        if (body.content() != null) {
            linkPreviews.collectAfterSave(markdown.toSafeHtml(body.content()), me.memberId());
        }
    }

    @DeleteMapping("/api/posts/{id}")
    public Map<String, Boolean> delete(@PathVariable long id, @AuthenticationPrincipal MemberPrincipal me) {
        posts.delete(id, me.memberId());
        return Map.of("deleted", true);
    }

    // ----- 카테고리 (BRD-03) -----

    @GetMapping("/api/blogs/{slug}/categories")
    public List<PostQueryService.CategoryRow> categories(@PathVariable String slug,
            @RequestParam(required = false) String key, @AuthenticationPrincipal MemberPrincipal me) {
        return query.categories(slug, key, id(me));
    }

    @PostMapping("/api/blogs/{slug}/categories")
    @PreAuthorize("@blogAuth.canManagePosts(#slug)")
    public Map<String, Long> addCategory(@PathVariable String slug, @RequestBody NameBody body) {
        return Map.of("id", categories.add(slug, body.name()));
    }

    @PutMapping("/api/blogs/{slug}/categories/{categoryId}")
    @PreAuthorize("@blogAuth.canManagePosts(#slug)")
    public Map<String, Boolean> renameCategory(@PathVariable String slug, @PathVariable long categoryId,
            @RequestBody NameBody body) {
        categories.rename(slug, categoryId, body.name());
        return Map.of("updated", true);
    }

    @DeleteMapping("/api/blogs/{slug}/categories/{categoryId}")
    @PreAuthorize("@blogAuth.canManagePosts(#slug)")
    public Map<String, Boolean> deleteCategory(@PathVariable String slug, @PathVariable long categoryId) {
        categories.delete(slug, categoryId);
        return Map.of("deleted", true);
    }

    private static Long id(MemberPrincipal me) {
        return me == null ? null : me.memberId();
    }

    public record NameBody(String name) {
    }
}
