package com.blognm.mainblog.social;

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
import org.springframework.web.multipart.MultipartFile;

import com.blognm.mainblog.auth.MemberPrincipal;
import com.blognm.mainblog.board.PostCards;
import com.blognm.mainblog.common.ApiException;
import com.blognm.mainblog.image.Image;
import com.blognm.mainblog.image.ImageService;

/** 팔로우·구독·차단(SOC-01·05)과 프로필·내 활동(SOC-03·07, D-91). 프로필과 목록은 비회원도 본다. */
@RestController
public class SocialController {

    private final FollowService follows;
    private final BlockService blocks;
    private final ProfileService profiles;
    private final ImageService images;

    public SocialController(FollowService follows, BlockService blocks, ProfileService profiles, ImageService images) {
        this.follows = follows;
        this.blocks = blocks;
        this.profiles = profiles;
        this.images = images;
    }

    // ----- 프로필·내 활동 -----

    @GetMapping("/api/members/{id}")
    public ProfileService.Profile profile(@PathVariable long id, @AuthenticationPrincipal MemberPrincipal me) {
        return profiles.profile(id, viewer(me));
    }

    @GetMapping("/api/members/{id}/posts")
    public PostCards.Page posts(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
            @AuthenticationPrincipal MemberPrincipal me) {
        return profiles.posts(id, viewer(me), ProfileService.ActivityList.POSTS, page);
    }

    @GetMapping("/api/members/{id}/likes")
    public PostCards.Page likes(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
            @AuthenticationPrincipal MemberPrincipal me) {
        return profiles.posts(id, viewer(me), ProfileService.ActivityList.LIKES, page);
    }

    @GetMapping("/api/members/{id}/followers")
    public ProfileService.MemberPage followers(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
            @AuthenticationPrincipal MemberPrincipal me) {
        return profiles.people(id, viewer(me), true, page);
    }

    @GetMapping("/api/members/{id}/following")
    public ProfileService.MemberPage following(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
            @AuthenticationPrincipal MemberPrincipal me) {
        return profiles.people(id, viewer(me), false, page);
    }

    @GetMapping("/api/members/{id}/subscriptions")
    public ProfileService.BlogPage subscriptions(@PathVariable long id, @RequestParam(defaultValue = "1") int page,
            @AuthenticationPrincipal MemberPrincipal me) {
        return profiles.subscriptions(id, viewer(me), page);
    }

    // ----- 팔로우·구독·차단 (누르면 하고, 한 번 더 누르면 취소) -----

    @PostMapping("/api/members/{id}/follow")
    public FollowService.Toggle follow(@PathVariable long id, @AuthenticationPrincipal MemberPrincipal me) {
        return follows.follow(me.memberId(), id);
    }

    @PostMapping("/api/blogs/{slug}/subscribe")
    public FollowService.Toggle subscribe(@PathVariable String slug, @RequestParam(required = false) String key,
            @AuthenticationPrincipal MemberPrincipal me) {
        return follows.subscribe(me.memberId(), slug, key);
    }

    @PostMapping("/api/members/{id}/block")
    public Map<String, Boolean> block(@PathVariable long id, @AuthenticationPrincipal MemberPrincipal me) {
        if (id == me.memberId()) {
            throw ApiException.badRequest("SELF_BLOCK", "자기 자신은 차단할 수 없습니다.");
        }
        follows.requireActive(id);
        follows.limit("block", me.memberId(), "차단");
        return Map.of("blocked", blocks.toggle(me.memberId(), id));
    }

    // ----- 내 정보: 소개·사진·공개 설정·차단 목록 -----

    @PutMapping("/api/me/bio")
    public Map<String, Boolean> bio(@RequestBody BioBody body, @AuthenticationPrincipal MemberPrincipal me) {
        profiles.updateBio(me.memberId(), body.bio());
        return Map.of("updated", true);
    }

    @PostMapping("/api/me/profile-image")
    public Map<String, String> uploadImage(@RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal MemberPrincipal me) {
        Image image = images.upload(me.memberId(), file);
        return Map.of("url", profiles.changeImage(me.memberId(), image.getId()));
    }

    @DeleteMapping("/api/me/profile-image")
    public Map<String, Boolean> deleteImage(@AuthenticationPrincipal MemberPrincipal me) {
        profiles.changeImage(me.memberId(), null);
        return Map.of("deleted", true);
    }

    @PutMapping("/api/me/activity-visibility")
    public ProfileService.Visibility visibility(@RequestBody ProfileService.Visibility body,
            @AuthenticationPrincipal MemberPrincipal me) {
        profiles.updateVisibility(me.memberId(), body);
        return body;
    }

    @GetMapping("/api/me/blocks")
    public List<ProfileService.MemberCard> blocked(@AuthenticationPrincipal MemberPrincipal me) {
        return profiles.blocked(me.memberId());
    }

    private static Long viewer(MemberPrincipal me) {
        return me == null ? null : me.memberId();
    }

    public record BioBody(String bio) {
    }
}
