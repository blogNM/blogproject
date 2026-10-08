package com.blognm.mainblog.notice;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.blognm.mainblog.auth.AdminPrincipal;

/** 메인 공지사항 (BRD-10). 읽기는 누구나, 쓰기는 메인 관리자만 (/api/admin/**). */
@Controller
public class NoticeController {

    private final NoticeService notices;

    public NoticeController(NoticeService notices) {
        this.notices = notices;
    }

    @GetMapping({"/notices", "/notices/{id}"})
    public String page() {
        return "forward:/notice.html";
    }

    @GetMapping("/api/notices/all")
    @ResponseBody
    public NoticeService.Page list(@RequestParam(defaultValue = "1") int page) {
        return notices.list(page);
    }

    @GetMapping("/api/notices/{id:\\d+}")
    @ResponseBody
    public NoticeService.Detail get(@PathVariable long id) {
        return notices.get(id);
    }

    @GetMapping("/api/admin/notices")
    @ResponseBody
    public NoticeService.Page adminList(@RequestParam(defaultValue = "1") int page) {
        return notices.list(page);
    }

    @PostMapping("/api/admin/notices")
    @ResponseBody
    public Map<String, Long> create(@RequestBody Body body, @AuthenticationPrincipal AdminPrincipal me) {
        return Map.of("id", notices.create(me.adminId(), body.title(), body.content()));
    }

    @PutMapping("/api/admin/notices/{id}")
    @ResponseBody
    public Map<String, Boolean> update(@PathVariable long id, @RequestBody Body body, @AuthenticationPrincipal AdminPrincipal me) {
        notices.update(me.adminId(), id, body.title(), body.content());
        return Map.of("updated", true);
    }

    @DeleteMapping("/api/admin/notices/{id}")
    @ResponseBody
    public Map<String, Boolean> delete(@PathVariable long id, @AuthenticationPrincipal AdminPrincipal me) {
        notices.delete(me.adminId(), id);
        return Map.of("deleted", true);
    }

    public record Body(String title, String content) {
    }
}
