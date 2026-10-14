package com.blognm.mainblog.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

import com.blognm.mainblog.config.AppProperties;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 구글·네이버·카카오 로그인에 다녀오는 동안 기억할 값(state, PKCE code_verifier, nonce 등)을 세션 대신 쿠키에 둔다 (D-111, SCL-01).
 * 스프링 기본(HttpSessionOAuth2AuthorizationRequestRepository)은 서버 메모리의 세션에 두어, 서버가 두 대일 때
 * 로그인 버튼을 누른 서버와 회사에서 돌아온 요청을 받은 서버가 다르면 "authorization_request_not_found"로 실패한다.
 * <ul>
 * <li>값은 JWT 서명 키에서 만든 키로 AES-GCM 암호화한다. 고치거나 다른 키로 만든 값은 읽지 않는다 (두 서버는 같은 secrets.env를 쓴다).</li>
 * <li>만든 시각을 암호문 안에 넣어 3분이 지나면 서버도 받지 않는다 (쿠키 Max-Age만 믿지 않음).</li>
 * <li>HttpOnly, SameSite=Lax(회사 화면에서 돌아오는 것은 최상위 GET 이동이라 쿠키가 간다), https면 Secure,
 * 경로는 돌아오는 주소(/login/oauth2/code)에만 보낸다. 한 번 쓰면 지운다.</li>
 * <li>너무 긴 값(브라우저 쿠키 한도 4KB 근처)은 쓰지도 읽지도 않는다.</li>
 * </ul>
 */
@Component
public class CookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final Logger log = LoggerFactory.getLogger(CookieOAuth2AuthorizationRequestRepository.class);

    public static final String COOKIE = "OAUTH2_REQUEST";
    /** 회사에서 돌아오는 주소(/login/oauth2/code/{회사})에만 쿠키를 보낸다. */
    public static final String PATH = "/login/oauth2/code";
    static final Duration TTL = Duration.ofMinutes(3);
    /** 쿠키 값 최대 길이. 이름·속성까지 4096바이트 안에 들어가게 한다. */
    static final int MAX_VALUE_LENGTH = 3800;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final boolean secure;
    private final Clock clock;

    public CookieOAuth2AuthorizationRequestRepository(AppProperties props, Clock clock) {
        String secret = props.auth().jwtSecret();
        // JwtProvider와 같은 조건: 비밀 키가 없으면 서버가 뜨지 않는다 (누구나 아는 키로 암호화하지 않게)
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.auth.jwt-secret(JWT_SECRET)은 32바이트 이상이어야 합니다.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("oauth2-authorization-request:" + secret).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        this.secure = props.auth().cookieSecure();
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (state == null) {
            return null;
        }
        OAuth2AuthorizationRequest saved = AuthCookies.read(request, COOKIE).map(this::decode).orElse(null);
        if (saved == null || saved.getState() == null) {
            return null;
        }
        boolean same = MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                saved.getState().getBytes(StandardCharsets.UTF_8));
        return same ? saved : null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
            HttpServletResponse response) {
        if (authorizationRequest == null) {
            write(request, response, "", Duration.ZERO);
            return;
        }
        String value = encode(authorizationRequest);
        if (value.length() > MAX_VALUE_LENGTH) {
            // 브라우저가 버리는 크기다. 돌아왔을 때 로그인 실패 화면으로 간다
            log.warn("소셜 로그인 요청 값이 쿠키에 담기에 너무 깁니다 ({}자, {})", value.length(),
                    authorizationRequest.getAttribute(OAuth2ParameterNames.REGISTRATION_ID));
            return;
        }
        write(request, response, value, TTL);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        OAuth2AuthorizationRequest saved = loadAuthorizationRequest(request);
        // 맞든 틀리든 돌아온 요청에서는 지운다 (다시 쓸 수 없게)
        if (AuthCookies.read(request, COOKIE).isPresent()) {
            write(request, response, "", Duration.ZERO);
        }
        return saved;
    }

    /**
     * 돌아왔을 때 필요한 값만 담는다. authorizationRequestUri는 나머지 값으로 다시 만들 수 있어 넣지 않는다 (쿠키 크기를 줄임).
     */
    String encode(OAuth2AuthorizationRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iat", clock.millis());
        m.put("authorizationUri", r.getAuthorizationUri());
        m.put("clientId", r.getClientId());
        m.put("redirectUri", r.getRedirectUri());
        m.put("scopes", new ArrayList<>(r.getScopes()));
        m.put("state", r.getState());
        m.put("additionalParameters", r.getAdditionalParameters());
        m.put("attributes", r.getAttributes());
        try {
            byte[] plain = json.writeValueAsBytes(m);
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(COOKIE.getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = cipher.doFinal(plain);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 바뀌었거나 다른 키로 만든 값, 3분이 지난 값, 모양이 틀린 값은 모두 null (로그인 실패로 처리된다). */
    @SuppressWarnings("unchecked")
    OAuth2AuthorizationRequest decode(String value) {
        if (value == null || value.length() > MAX_VALUE_LENGTH) {
            return null;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(value);
            if (raw.length <= IV_BYTES) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            cipher.updateAAD(COOKIE.getBytes(StandardCharsets.US_ASCII));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            Map<String, Object> m = json.readValue(plain, MAP);
            long issuedAt = ((Number) m.get("iat")).longValue();
            long age = clock.millis() - issuedAt;
            if (age < -Duration.ofMinutes(1).toMillis() || age > TTL.toMillis()) {
                return null;
            }
            return OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri((String) m.get("authorizationUri"))
                    .clientId((String) m.get("clientId"))
                    .redirectUri((String) m.get("redirectUri"))
                    .scopes(new LinkedHashSet<>((List<String>) m.get("scopes")))
                    .state((String) m.get("state"))
                    .additionalParameters((Map<String, Object>) m.get("additionalParameters"))
                    .attributes((Map<String, Object>) m.get("attributes"))
                    .build();
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(secure || request.isSecure())
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
