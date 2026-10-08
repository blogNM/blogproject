package com.blognm.mainblog.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.stereotype.Component;

import com.blognm.mainblog.config.AppProperties;

/**
 * Access Token. 토큰에는 회원 번호만 담는다 (헌법 II, SEC-07).
 * 역할·정지 여부는 토큰이 아니라 매 요청 DB로 확인한다.
 */
@Component
public class JwtProvider {

    private static final String TYPE_CLAIM = "typ";
    private static final String MEMBER_TYPE = "member";

    private final SecretKey key;
    private final AppProperties.Auth props;
    private final Clock clock;

    public JwtProvider(AppProperties props, Clock clock) {
        String secret = props.auth().jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 환경변수에 32바이트 이상 값을 넣어 주세요.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.props = props.auth();
        this.clock = clock;
    }

    public String createMemberToken(long memberId) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(Long.toString(memberId))
                .claim(TYPE_CLAIM, MEMBER_TYPE)
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(props.accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    /** 서명·만료가 맞는 회원 토큰이면 회원 번호를 돌려준다. */
    public Optional<Long> parseMemberId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!MEMBER_TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
