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
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final AccessTokenValidator validator;

    public JwtAuthenticationFilter(JwtProvider jwtProvider, AccessTokenValidator validator) {
        this.jwtProvider = jwtProvider;
        this.validator = validator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
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
