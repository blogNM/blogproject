package com.blognm.mainblog.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.blognm.mainblog.auth.AccessTokenValidator;
import com.blognm.mainblog.auth.AdminTokenCheck;
import com.blognm.mainblog.auth.JwtAuthenticationFilter;
import com.blognm.mainblog.auth.JwtProvider;

/**
 * 보안 헤더·CSRF·인증 (SEC-10, SEC-11, SEC-12, 헌법 III).
 * 주소 단위로 비회원·회원을 나누고, 블로그별 역할은 @PreAuthorize("@blogAuth...")로 매 요청 DB에서 확인한다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Turnstile 스크립트·iframe만 바깥 주소를 허용한다. 스크립트는 어느 화면에서도 인라인을 허용하지 않는다. */
    static final String CSP = csp("'self'");

    /**
     * 글쓰기 화면만 인라인 style을 허용한다. Toast UI Editor가 style 속성으로 화면을 그리기 때문이다.
     * 에디터 파일은 서버에 두고(/vendor/toastui) 바깥 주소에서 받지 않는다. 스크립트 규칙은 같다.
     */
    static final String EDITOR_CSP = csp("'self' 'unsafe-inline'");

    static final RequestMatcher EDITOR_PAGES = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/blog/*/write"),
            PathPatternRequestMatcher.withDefaults().matcher("/blog/*/posts/*/edit"),
            PathPatternRequestMatcher.withDefaults().matcher("/post-edit.html"));

    private static String csp(String styleSrc) {
        return String.join("; ",
                "default-src 'self'",
                "script-src 'self' https://challenges.cloudflare.com",
                "frame-src https://challenges.cloudflare.com",
                "style-src " + styleSrc,
                "img-src 'self' data:",
                "connect-src 'self'",
                "object-src 'none'",
                "base-uri 'self'",
                "form-action 'self'",
                "frame-ancestors 'none'");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider,
            AccessTokenValidator tokenValidator, AdminTokenCheck adminCheck, AppProperties props)
            throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(c -> c.secure(props.auth().cookieSecure()).sameSite("Lax"));
        // 화면(api.js)은 쿠키 값을 그대로 X-XSRF-TOKEN 헤더에 넣는다.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(csrfHandler)
                        // 릴리스 공지는 브라우저가 아니라 서버 안의 워크플로가 토큰 헤더로 부른다 (D-97)
                        .ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/release-notices")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> {
                        })
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(EDITOR_PAGES,
                                new StaticHeadersWriter("Content-Security-Policy", EDITOR_CSP)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(EDITOR_PAGES),
                                new StaticHeadersWriter("Content-Security-Policy", CSP))))
                .authorizeHttpRequests(auth -> auth
                        // 관리자 페이지는 주소 단위로 관리자만 (SEC-11). 로그인 화면과 로그인 요청만 열어 둔다.
                        .requestMatchers("/admin/login.html", "/api/admin/auth/login").permitAll()
                        .requestMatchers("/admin", "/admin/**", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/**", "/api/csrf").permitAll()
                        // 릴리스 공지: 로그인 대신 서버에만 있는 토큰으로 확인한다 (D-97)
                        .requestMatchers(HttpMethod.POST, "/api/release-notices").permitAll()
                        // 블로그 목록·검색·블로그 정보는 비회원도 본다. 비공개 여부는 서비스가 다시 확인한다.
                        .requestMatchers(HttpMethod.GET, "/api/blogs", "/api/blogs/*").permitAll()
                        // 글 목록·카테고리·글 상세도 공개 범위 안에서 비회원이 읽는다 (004).
                        .requestMatchers(HttpMethod.GET, "/api/blogs/*/posts", "/api/blogs/*/categories",
                                "/api/posts/*", "/api/posts/*/comments").permitAll()
                        // 메인 피드·공지·통합 검색 (004 4단계). 팔로우·구독 탭은 서비스가 로그인을 확인한다.
                        .requestMatchers(HttpMethod.GET, "/api/feed", "/api/notices", "/api/notices/*", "/api/search").permitAll()
                        // 프로필·팔로워·내 활동 목록 (5단계). 비공개로 둔 목록은 서비스가 막는다 (D-91).
                        .requestMatchers(HttpMethod.GET, "/api/members/*", "/api/members/*/*").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            String uri = req.getRequestURI();
                            if (uri.equals("/admin") || uri.startsWith("/admin/")) {
                                res.sendRedirect("/admin/login.html");
                            } else {
                                writeError(res, HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다.");
                            }
                        })
                        .accessDeniedHandler((req, res, ex) -> writeError(res, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "요청을 처리할 수 없습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, tokenValidator, adminCheck), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 비밀번호는 bcrypt로만 저장한다 (SEC-01, D-81). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void writeError(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, String code,
            String message) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
