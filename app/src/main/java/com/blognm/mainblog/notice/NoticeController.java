package com.blognm.mainblog.notice;

import java.util.List;
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
import org.springframework.web.multipart.MultipartFile;

import com.blognm.mainblog.auth.AdminPrincipal;
import com.blognm.mainblog.image.Image;
import com.blognm.mainblog.image.ImageService;

/** 메인 공지사항 (BRD-10). 읽기는 누구나, 쓰기는 메인 관리자만 (/api/admin/**). */
@Controller
public class NoticeController {

    private final NoticeService notices;
    private final ImageService images;

    public NoticeController(NoticeService notices, ImageService images) {
        this.notices = notices;
        this.images = images;
    }

    @GetMapping({"/notices", "/notices/{id}"})
    public String page() {
        return "forward:/notice.html";
    }

    /** 사용 방법 안내를 옆으로 넘겨 보는 화면 (D-104). */
    @GetMapping("/guides")
    public String guidePage() {
        return "forward:/guide.html";
    }

    /** 사용 방법 안내 공지의 제목과 본문 (D-104). 비회원도 본다 (SecurityConfig의 GET /api/notices/*). */
    @GetMapping("/api/notices/guides")
    @ResponseBody
    public List<NoticeService.Guide> guides() {
        return notices.guides();
    }

    @GetMapping("/api/notices/all")
    @ResponseBody
    public NoticeService.Page list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) NoticeService.Category category) {
        return notices.list(page, category);
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
        return Map.of("id", notices.create(me.adminId(), body.title(), body.content(), body.category()));
    }

    @PutMapping("/api/admin/notices/{id}")
    @ResponseBody
    public Map<String, Boolean> update(@PathVariable long id, @RequestBody Body body, @AuthenticationPrincipal AdminPrincipal me) {
        notices.update(me.adminId(), id, body.title(), body.content(), body.category());
        return Map.of("updated", true);
    }

    /** 공지에 넣을 사진 올리기. 메인 관리자만 (/api/admin/**). 돌려준 주소를 본문에 마크다운 그림으로 넣는다 (D-102). */
    @PostMapping("/api/admin/notices/images")
    @ResponseBody
    public Map<String, Object> uploadImage(@RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AdminPrincipal me) {
        Image image = images.uploadByAdmin(me.adminId(), file);
        return Map.of("id", image.getId(), "url", image.url());
    }

    /** 공지 편집기의 미리보기. 실제 공지와 같은 변환·거르기를 거친 HTML (D-102). */
    @PostMapping("/api/admin/notices/preview")
    @ResponseBody
    public Map<String, String> preview(@RequestBody PreviewBody body) {
        return Map.of("html", notices.preview(body.content()));
    }

    @DeleteMapping("/api/admin/notices/{id}")
    @ResponseBody
    public Map<String, Boolean> delete(@PathVariable long id, @AuthenticationPrincipal AdminPrincipal me) {
        notices.delete(me.adminId(), id);
        return Map.of("deleted", true);
    }

    public record PreviewBody(String content) {
    }

    /** category는 비우면 일반 공지(NOTICE) (D-98). */
    public record Body(String title, String content, NoticeService.Category category) {
    }
}
