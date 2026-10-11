package com.blognm.mainblog.auth;

import java.time.Duration;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.blognm.mainblog.config.AppProperties;

/** 로그인 토큰은 HttpOnly·Secure 쿠키에만 담는다 (SEC-04). */
@Component
public class AuthCookies {

    public static final String ACCESS = "ACCESS_TOKEN";
    public static final String REFRESH = "REFRESH_TOKEN";
    /** 관리자 로그인. 회원 쿠키와 따로 두고, 브라우저를 닫으면 사라진다 (SEC-09). */
    public static final String ADMIN = "ADMIN_TOKEN";
    /** Refresh Token 쿠키는 토큰 갱신·로그아웃 주소에만 보낸다. */
    static final String REFRESH_PATH = "/api/auth";

    private final AppProperties.Auth props;

    public AuthCookies(AppProperties props) {
        this.props = props.auth();
    }

    /** rememberMe가 아니면 Max-Age 없는 세션 쿠키라 브라우저를 닫으면 사라진다 (D-61). */
    public void write(HttpServletResponse response, String accessToken, String refreshToken, boolean rememberMe) {
        Duration maxAge = rememberMe ? props.rememberMeTtl() : null;
        add(response, ACCESS, accessToken, "/", maxAge);
        add(response, REFRESH, refreshToken, REFRESH_PATH, maxAge);
    }

    public void writeAccess(HttpServletResponse response, String accessToken, boolean rememberMe) {
        add(response, ACCESS, accessToken, "/", rememberMe ? props.rememberMeTtl() : null);
    }

    public void clear(HttpServletResponse response) {
        add(response, ACCESS, "", "/", Duration.ZERO);
        add(response, REFRESH, "", REFRESH_PATH, Duration.ZERO);
    }

    public void writeAdmin(HttpServletResponse response, String token) {
        add(response, ADMIN, token, "/", null);
    }

    public void clearAdmin(HttpServletResponse response) {
        add(response, ADMIN, "", "/", Duration.ZERO);
    }

    public static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    private void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path(path);
        if (maxAge != null) {
            b.maxAge(maxAge);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, b.build().toString());
    }
}
