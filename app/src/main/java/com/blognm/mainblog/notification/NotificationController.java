package com.blognm.mainblog.notification;

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

/** 알림 (SOC-04). 화면은 30초마다 unread를 부른다. 이 요청은 30분 자동 로그아웃 시간을 늘리지 않는다 (D-62). */
@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/notifications")
    public NotificationService.Page list(@RequestParam(defaultValue = "ALL") String tab,
            @RequestParam(defaultValue = "1") int page, @AuthenticationPrincipal MemberPrincipal me) {
        return notifications.list(me.memberId(), tab, page);
    }

    @GetMapping("/api/notifications/unread")
    public Map<String, Long> unread(@AuthenticationPrincipal MemberPrincipal me) {
        return notifications.unread(me.memberId());
    }

    @PostMapping("/api/notifications/{id}/read")
    public Map<String, Boolean> read(@PathVariable long id, @AuthenticationPrincipal MemberPrincipal me) {
        notifications.read(me.memberId(), id);
        return Map.of("read", true);
    }

    @PostMapping("/api/notifications/read-all")
    public Map<String, Boolean> readAll(@RequestParam(defaultValue = "ALL") String tab,
            @AuthenticationPrincipal MemberPrincipal me) {
        notifications.readAll(me.memberId(), tab);
        return Map.of("read", true);
    }

    @DeleteMapping("/api/notifications")
    public Map<String, Boolean> deleteAll(@RequestParam(defaultValue = "ALL") String tab,
            @AuthenticationPrincipal MemberPrincipal me) {
        notifications.deleteAll(me.memberId(), tab);
        return Map.of("deleted", true);
    }

    @GetMapping("/api/me/notification-settings")
    public NotificationService.Settings settings(@AuthenticationPrincipal MemberPrincipal me) {
        return notifications.settings(me.memberId());
    }

    @PutMapping("/api/me/notification-settings")
    public NotificationService.Settings updateSettings(@RequestBody SettingsBody body,
            @AuthenticationPrincipal MemberPrincipal me) {
        return notifications.updateSettings(me.memberId(), body.enabled(), body.retentionDays());
    }

    @GetMapping("/api/blogs/{slug}/notification-retention")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, Integer> blogRetention(@PathVariable String slug) {
        return Map.of("days", notifications.blogRetention(slug));
    }

    @PutMapping("/api/blogs/{slug}/notification-retention")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, Integer> updateBlogRetention(@PathVariable String slug, @RequestBody RetentionBody body) {
        notifications.updateBlogRetention(slug, body.days());
        return Map.of("days", body.days());
    }

    @DeleteMapping("/api/blogs/{slug}/notifications")
    @PreAuthorize("@blogAuth.isOwner(#slug)")
    public Map<String, Integer> deleteBlogNotifications(@PathVariable String slug) {
        return Map.of("deleted", notifications.deleteBlogNotifications(slug));
    }

    public record SettingsBody(Map<String, Boolean> enabled, Integer retentionDays) {
    }

    public record RetentionBody(int days) {
    }
}
