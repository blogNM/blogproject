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

/** ACCESS_TOKEN 쿠키가 맞으면 회원으로 로그인시킨다. 틀리거나 만료됐으면 비회원으로 둔다. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    public JwtAuthenticationFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthCookies.read(request, AuthCookies.ACCESS)
                .flatMap(jwtProvider::parseMemberId)
                .ifPresent(memberId -> {
                    var auth = new UsernamePasswordAuthenticationToken(new MemberPrincipal(memberId), null,
                            List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
        chain.doFilter(request, response);
    }
}
