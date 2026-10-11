package com.blognm.mainblog.board;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.auth.MemberPrincipal;
import com.blognm.mainblog.common.ApiException;

/** 메인 피드·공지 (BRD-09·10)와 통합 검색 (BRD-08). 피드의 최신·인기 탭과 검색은 비회원도 쓴다. */
@RestController
public class BoardController {

    private final FeedService feed;
    private final SearchService search;

    public BoardController(FeedService feed, SearchService search) {
        this.feed = feed;
        this.search = search;
    }

    @GetMapping("/api/feed")
    public PostCards.Page feed(@RequestParam(defaultValue = "latest") String tab,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal MemberPrincipal me) {
        return feed.feed(parse(FeedService.Tab.class, tab), id(me), page, size);
    }

    @GetMapping("/api/notices")
    public List<FeedService.Notice> notices() {
        return feed.notices();
    }

    @GetMapping("/api/search")
    public SearchService.Result search(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "post") String type, @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal MemberPrincipal me) {
        return search.search(q, parse(SearchService.Type.class, type), parse(SearchService.Sort.class, sort), page,
                size, id(me));
    }

    @GetMapping("/api/me/recent-searches")
    public List<SearchService.Recent> recent(@AuthenticationPrincipal MemberPrincipal me) {
        return search.recent(me.memberId());
    }

    @DeleteMapping("/api/me/recent-searches")
    public Map<String, Boolean> clearRecent(@AuthenticationPrincipal MemberPrincipal me) {
        search.deleteRecent(me.memberId(), null);
        return Map.of("deleted", true);
    }

    @DeleteMapping("/api/me/recent-searches/{id}")
    public Map<String, Boolean> deleteRecent(@PathVariable long id, @AuthenticationPrincipal MemberPrincipal me) {
        search.deleteRecent(me.memberId(), id);
        return Map.of("deleted", true);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_INPUT", "입력값을 확인해 주세요.");
        }
    }

    private static Long id(MemberPrincipal me) {
        return me == null ? null : me.memberId();
    }
}
