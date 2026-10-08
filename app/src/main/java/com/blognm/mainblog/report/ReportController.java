package com.blognm.mainblog.report;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.auth.MemberPrincipal;

@RestController
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/api/reports")
    public Map<String, String> report(@RequestBody ReportService.ReportRequest body,
            @AuthenticationPrincipal MemberPrincipal me) {
        return Map.of("handler", reports.report(me.memberId(), body));
    }
}
