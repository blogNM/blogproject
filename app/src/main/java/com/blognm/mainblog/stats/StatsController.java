package com.blognm.mainblog.stats;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.auth.MemberPrincipal;
import com.blognm.mainblog.common.ClientIp;

/** 블로그 통계 (D-112). 방문 기록은 누구나 보내고, 통계는 블로그장·부블로그장만 본다. */
@RestController
public class StatsController {

    private final VisitService visits;
    private final StatsService stats;

    public StatsController(VisitService visits, StatsService stats) {
        this.visits = visits;
        this.stats = stats;
    }

    /** 블로그 홈·글 화면이 열릴 때 visit.js가 보낸다. 세지 않는 방문이어도 늘 204라 화면은 결과를 신경 쓰지 않는다. */
    @PostMapping("/api/blogs/{slug}/visits")
    public ResponseEntity<Void> visit(@PathVariable String slug, @RequestBody VisitService.VisitRequest body,
            @AuthenticationPrincipal MemberPrincipal me, HttpServletRequest request) {
        visits.record(slug, body, me == null ? null : me.memberId(), ClientIp.of(request),
                request.getHeader("User-Agent"), request.getServerName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/blogs/{slug}/stats")
    @PreAuthorize("@blogAuth.canViewStats(#slug)")
    public StatsService.Stats stats(@PathVariable String slug, @RequestParam(defaultValue = "day") String unit,
            @RequestParam(required = false) String date, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer months) {
        return stats.stats(slug, unit, date, from, to, months);
    }
}
