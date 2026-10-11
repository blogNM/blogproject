package com.blognm.mainblog.comment;

import java.util.List;
import java.util.Map;

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

/** 댓글 API (004 US5). 목록은 글을 읽을 수 있으면 비회원도 본다. 일부 공개 블로그는 공유 키(key)를 같이 보낸다. */
@RestController
public class CommentController {

    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/api/posts/{postId}/comments")
    public List<CommentService.CommentView> list(@PathVariable long postId, @RequestParam(required = false) String key,
            @AuthenticationPrincipal MemberPrincipal me) {
        return comments.list(postId, key, me == null ? null : me.memberId());
    }

    @PostMapping("/api/posts/{postId}/comments")
    public Map<String, Long> write(@PathVariable long postId, @RequestParam(required = false) String key,
            @RequestBody CommentService.WriteRequest body, @AuthenticationPrincipal MemberPrincipal me) {
        return Map.of("id", comments.write(postId, key, me.memberId(), body));
    }

    @PutMapping("/api/comments/{commentId}")
    public Map<String, Boolean> update(@PathVariable long commentId, @RequestParam(required = false) String key,
            @RequestBody CommentService.WriteRequest body, @AuthenticationPrincipal MemberPrincipal me) {
        comments.update(commentId, key, me.memberId(), body.content());
        return Map.of("updated", true);
    }

    @DeleteMapping("/api/comments/{commentId}")
    public Map<String, Boolean> delete(@PathVariable long commentId, @RequestParam(required = false) String key,
            @AuthenticationPrincipal MemberPrincipal me) {
        comments.delete(commentId, key, me.memberId());
        return Map.of("deleted", true);
    }
}
