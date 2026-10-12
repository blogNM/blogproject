package com.blognm.mainblog.notice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.blognm.mainblog.common.ApiException;

/**
 * 릴리스 공지 (D-97). 깃허브 Actions의 릴리스 워크플로가 서버에 SSH로 들어가 이 주소를 부른다.
 * 서버의 secrets.env에만 있는 RELEASE_NOTICE_TOKEN을 X-Release-Token 헤더로 맞춰야 한다. 값이 없으면 주소가 없는 것처럼 404.
 */
@RestController
public class ReleaseNoticeController {

    private final NoticeService notices;
    private final byte[] token;

    public ReleaseNoticeController(NoticeService notices, @Value("${app.release-notice-token:}") String token) {
        this.notices = notices;
        this.token = token.strip().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/release-notices")
    public Map<String, Long> publish(@RequestHeader(name = "X-Release-Token", required = false) String given,
            @RequestBody NoticeController.Body body) {
        if (token.length == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "찾을 수 없습니다.");
        }
        if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "요청을 처리할 수 없습니다.");
        }
        return Map.of("id", notices.publishRelease(body.title(), body.content()));
    }
}
