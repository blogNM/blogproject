package com.blognm.mainblog.config;

import java.util.Map;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** XSRF-TOKEN 쿠키가 없을 때 api.js가 한 번 불러 쿠키를 받는다 (SEC-10). */
@RestController
public class CsrfController {

    @GetMapping("/api/csrf")
    public Map<String, Boolean> csrf(CsrfToken token) {
        token.getToken(); // 토큰을 읽어야 쿠키가 만들어진다
        return Map.of("ok", true);
    }
}
