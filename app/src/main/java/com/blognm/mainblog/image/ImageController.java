package com.blognm.mainblog.image;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.blognm.mainblog.auth.MemberPrincipal;

/** 글에 넣을 사진 올리기와 보여주기 (BRD-05). */
@RestController
public class ImageController {

    private static final Pattern UUID_NAME = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ImageService images;

    public ImageController(ImageService images) {
        this.images = images;
    }

    /** 그 블로그에 글을 쓸 수 있는 멤버만 올린다. 글을 저장할 때 본문에 든 사진이 그 글에 붙는다. */
    @PostMapping("/api/blogs/{slug}/images")
    @PreAuthorize("@blogAuth.isMember(#slug)")
    public Map<String, Object> upload(@PathVariable String slug, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal MemberPrincipal me) {
        Image image = images.upload(me.memberId(), file);
        return Map.of("id", image.getId(), "url", image.url());
    }

    /**
     * 이름이 추측할 수 없는 UUID라 따로 권한을 보지 않는다. 형식은 DB에 저장한 값으로 내려 보낸다.
     * 파일은 통째로 읽지 않고 조금씩 흘려 보낸다(힙 512MB를 사진이 차지하지 않게). 같은 이름의 내용은 바뀌지 않으므로
     * 브라우저가 1년 동안 다시 묻지 않게 하고(immutable), 다시 물으면 ETag로 304만 돌려준다 (D-132).
     */
    @GetMapping("/uploads/{name}")
    public ResponseEntity<Resource> serve(@PathVariable String name) {
        if (!UUID_NAME.matcher(name).matches()) {
            return ResponseEntity.notFound().build();
        }
        return images.read(name)
                .map(s -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(s.mimeType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .eTag(name)
                        .body(s.file()))
                .orElse(ResponseEntity.notFound().build());
    }
}
