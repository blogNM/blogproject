package com.blognm.mainblog.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import com.blognm.mainblog.auth.JwtAuthenticationFilter;
import com.blognm.mainblog.auth.JwtProvider;

/**
 * 보안 헤더·CSRF·인증 (SEC-10, SEC-11, SEC-12, 헌법 III).
 * 주소 단위로 비회원·회원을 나누고, 블로그별 역할은 2단계부터 @PreAuthorize로 확인한다.
 */
@Configuration
public class SecurityConfig {

    /** Turnstile 스크립트·iframe만 바깥 주소를 허용한다. 3단계에서 Toast UI Editor를 붙일 때 늘린다. */
    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://challenges.cloudflare.com",
            "frame-src https://challenges.cloudflare.com",
            "style-src 'self'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider, AppProperties props)
            throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(c -> c.secure(props.auth().cookieSecure()).sameSite("Lax"));
        // 화면(api.js)은 쿠키 값을 그대로 X-XSRF-TOKEN 헤더에 넣는다.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(csrfHandler))
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
                        .contentSecurityPolicy(c -> c.policyDirectives(CSP)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/csrf").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, HttpStatus.UNAUTHORIZED,
                                "LOGIN_REQUIRED", "로그인이 필요합니다."))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "요청을 처리할 수 없습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
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
