package com.blognm.mainblog.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ACCESS_TOKEN 쿠키가 맞으면 회원으로 로그인시킨다. 틀리거나 만료됐거나,
 * 탈퇴·비밀번호 변경 전에 발급된 토큰이면 비회원으로 둔다.
 * 정적 파일 요청마다 DB를 보지 않도록 /api/ 요청에서만 확인한다.
 * 관리자 주소(/api/admin/, /admin/)에서는 회원 쿠키 대신 ADMIN_TOKEN만 본다. 관리자 토큰은 다른 주소에서 쓰이지 않아
 * 관리자가 회원 기능을 쓰는 일이 없다 (SEC-09, SEC-11, D-84).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final AccessTokenValidator validator;
    private final AdminTokenCheck adminCheck;

    public JwtAuthenticationFilter(JwtProvider jwtProvider, AccessTokenValidator validator, AdminTokenCheck adminCheck) {
        this.jwtProvider = jwtProvider;
        this.validator = validator;
        this.adminCheck = adminCheck;
    }

    static boolean isAdminPath(String uri) {
        return uri.startsWith("/api/admin/") || uri.startsWith("/admin/") || uri.equals("/admin");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") && !isAdminPath(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAdminPath(request.getRequestURI())) {
            AuthCookies.read(request, AuthCookies.ADMIN)
                    .flatMap(jwtProvider::parseAdminToken)
                    .filter(adminCheck::isValid)
                    .ifPresent(token -> SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(new AdminPrincipal(token.memberId()), null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
            chain.doFilter(request, response);
            return;
        }
        AuthCookies.read(request, AuthCookies.ACCESS)
                .flatMap(jwtProvider::parseMemberToken)
                .filter(validator::isValid)
                .ifPresent(token -> {
                    var auth = new UsernamePasswordAuthenticationToken(new MemberPrincipal(token.memberId()), null,
                            List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
        chain.doFilter(request, response);
    }
}
