package com.blognm.mainblog.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 회사에서 받은 접근 토큰을 어디에도 두지 않는다 (D-111, SCL-01).
 * 로그인할 때 이메일·회원 번호만 읽고 그 뒤로 구글·네이버·카카오 API를 부르지 않는다.
 * 스프링 기본은 서버 메모리(InMemoryOAuth2AuthorizedClientService)에 로그인할 때마다 쌓아 두어, 서버마다 달라지고 계속 늘어난다.
 */
public class NoStoreAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
            Authentication principal, HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
            HttpServletRequest request, HttpServletResponse response) {
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
            HttpServletRequest request, HttpServletResponse response) {
    }
}
