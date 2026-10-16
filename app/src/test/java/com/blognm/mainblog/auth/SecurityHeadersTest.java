package com.blognm.mainblog.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import com.blognm.mainblog.support.IntegrationTest;

/**
 * SEC-12 보안 헤더, SEC-10 CSRF 쿠키.
 * 다른 테스트의 csrf() 요청 도우미가 공유 컨텍스트의 CSRF 저장소를 세션 방식으로 바꿔 두므로,
 * 실제 쿠키 저장소를 확인하려면 새 컨텍스트에서 돌린다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SecurityHeadersTest extends IntegrationTest {

    @Test
    @DisplayName("화면 응답에 보안 헤더가 붙는다")
    void securityHeaders() throws Exception {
        mvc.perform(get("/index.html").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Content-Security-Policy", Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("Strict-Transport-Security", Matchers.containsString("max-age=")))
                .andExpect(header().string("Permissions-Policy", Matchers.allOf(
                        Matchers.containsString("camera=()"), Matchers.containsString("geolocation=()"))))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"));
    }

    @Test
    @DisplayName("/api/csrf를 부르면 화면 JS가 읽을 수 있는 XSRF-TOKEN 쿠키가 생긴다")
    void csrfCookie() throws Exception {
        mvc.perform(get("/api/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", Matchers.allOf(
                        Matchers.startsWith("XSRF-TOKEN="), Matchers.not(Matchers.containsString("HttpOnly")))));
    }

    @Test
    @DisplayName("로그인 없이 /api/me를 부르면 401 JSON이다")
    void unauthenticated() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", Matchers.containsString("application/json")));
    }

    @Test
    @DisplayName("없는 화면 주소는 404 안내 화면(HTML)을 보낸다")
    void notFoundPage() throws Exception {
        mvc.perform(get("/no-such-page"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", Matchers.containsString("text/html")));
    }

    @Test
    @DisplayName("정적 파일은 캐시한다: 글꼴·그림은 오래 두고, 화면·js·css는 쓸 때마다 바뀌었는지 묻는다. API는 그대로 no-store (D-109)")
    void staticCache() throws Exception {
        mvc.perform(get("/vendor/pretendard/PretendardVariable.woff2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.allOf(
                        Matchers.containsString("max-age=2592000"), Matchers.not(Matchers.containsString("no-store")))));
        mvc.perform(get("/img/creat-mark.svg"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("max-age=604800")));
        for (String path : new String[] {"/js/api.js", "/css/common.css", "/search.html"}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andExpect(header().exists("Last-Modified"))
                    .andExpect(header().string("Content-Security-Policy", Matchers.containsString("default-src 'self'")));
        }
        mvc.perform(get("/api/me"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
    }
}
