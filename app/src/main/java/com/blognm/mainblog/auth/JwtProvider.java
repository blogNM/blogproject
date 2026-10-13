package com.blognm.mainblog.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
    /** 관리자 토큰은 회원 토큰과 종류가 달라 서로 대신 쓸 수 없다 (SEC-09). */
    private static final String ADMIN_TYPE = "admin";
    /** 구글 로그인 뒤 닉네임·약관 동의를 받는 동안만 쓰는 토큰. 로그인 토큰으로는 쓸 수 없다 (D-103). */
    private static final String SOCIAL_SIGNUP_TYPE = "social-signup";

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

    public String createAdminToken(long adminId, java.time.Duration ttl) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(Long.toString(adminId))
                .claim(TYPE_CLAIM, ADMIN_TYPE)
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(ttl)))
                .signWith(key)
                .compact();
    }

    public String createSocialSignupToken(SocialSignup signup, java.time.Duration ttl) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(signup.providerUserId())
                .claim(TYPE_CLAIM, SOCIAL_SIGNUP_TYPE)
                .claim("provider", signup.provider())
                .claim("email", signup.email())
                .claim("name", signup.name())
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 서명·만료가 맞는 가입 대기 토큰이면 구글에서 받은 정보를 돌려준다. */
    public Optional<SocialSignup> parseSocialSignupToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!SOCIAL_SIGNUP_TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(new SocialSignup(claims.get("provider", String.class), claims.getSubject(),
                    claims.get("email", String.class), claims.get("name", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 서명·만료가 맞는 관리자 토큰이면 관리자 번호와 발급 시각을 돌려준다. */
    public Optional<MemberToken> parseAdminToken(String token) {
        return parse(token, ADMIN_TYPE);
    }

    /** 서명·만료가 맞는 회원 토큰이면 회원 번호를 돌려준다. */
    public Optional<Long> parseMemberId(String token) {
        return parseMemberToken(token).map(MemberToken::memberId);
    }

    /** 서명·만료가 맞는 회원 토큰이면 회원 번호와 발급 시각을 돌려준다. */
    public Optional<MemberToken> parseMemberToken(String token) {
        return parse(token, MEMBER_TYPE);
    }

    private Optional<MemberToken> parse(String token, String type) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!type.equals(claims.get(TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            Instant issuedAt = claims.getIssuedAt() == null ? Instant.EPOCH : claims.getIssuedAt().toInstant();
            return Optional.of(new MemberToken(Long.parseLong(claims.getSubject()), issuedAt));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 구글 로그인으로 처음 온 사람의 정보. 가입을 마칠 때까지 서버에 두지 않고 서명한 쿠키에만 둔다 (SCL-01). */
    public record SocialSignup(String provider, String providerUserId, String email, String name) {
    }

    /** memberId: 회원 토큰이면 회원 번호, 관리자 토큰이면 관리자 번호. */
    public record MemberToken(long memberId, Instant issuedAt) {
    }
}
