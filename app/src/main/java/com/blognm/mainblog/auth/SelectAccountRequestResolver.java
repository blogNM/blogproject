package com.blognm.mainblog.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * 카카오·구글 로그인에서 매번 "어느 계정으로 로그인할까요" 화면을 띄운다 (D-108, D-103 후속).
 * 로그아웃한 뒤 다른 카카오·구글 계정으로 들어갈 수 있도록 로그인 요청에 prompt=select_account를 붙인다.
 * 네이버는 계정 고르기 화면이 없어 그대로 둔다 (다른 네이버 계정은 naver.com에서 로그아웃한 뒤 고른다).
 * 그 밖의 값(state, PKCE, nonce 등)은 스프링 기본 처리(DefaultOAuth2AuthorizationRequestResolver)를 그대로 쓴다.
 */
public class SelectAccountRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final Set<String> SELECT_ACCOUNT = Set.of(SocialAccount.KAKAO, SocialAccount.GOOGLE);

    private final DefaultOAuth2AuthorizationRequestResolver defaults;

    public SelectAccountRequestResolver(ClientRegistrationRepository clients) {
        this.defaults = new DefaultOAuth2AuthorizationRequestResolver(clients,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withPrompt(defaults.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return withPrompt(defaults.resolve(request, registrationId));
    }

    private static OAuth2AuthorizationRequest withPrompt(OAuth2AuthorizationRequest request) {
        if (request == null) {
            return null;
        }
        Object registrationId = request.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
        if (registrationId == null || !SELECT_ACCOUNT.contains(registrationId.toString())) {
            return request;
        }
        Map<String, Object> extra = new LinkedHashMap<>(request.getAdditionalParameters());
        extra.put("prompt", "select_account");
        return OAuth2AuthorizationRequest.from(request).additionalParameters(extra).build();
    }
}
