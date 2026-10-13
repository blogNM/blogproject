package com.blognm.mainblog.auth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * 구글(D-103)·네이버(D-107) 로그인 앱 등록. CLIENT_ID가 비어 있는 회사는 만들지 않고, 로그인 화면의 버튼도 숨긴다.
 * 값이 없어도 서버가 뜨도록 스프링 기본 설정(spring.security.oauth2.client.*) 대신 여기서 직접 만든다.
 */
@Configuration
@ConditionalOnExpression("!'${app.social.google.client-id:}'.isBlank() or !'${app.social.naver.client-id:}'.isBlank()")
public class SocialLoginConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.social.google.client-id:}") String googleId,
            @Value("${app.social.google.client-secret:}") String googleSecret,
            @Value("${app.social.naver.client-id:}") String naverId,
            @Value("${app.social.naver.client-secret:}") String naverSecret) {
        List<ClientRegistration> registrations = new ArrayList<>();
        if (!googleId.isBlank()) {
            registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder(SocialAccount.GOOGLE)
                    .clientId(googleId.trim())
                    .clientSecret(googleSecret.trim())
                    .scope("openid", "email", "profile")
                    .build());
        }
        if (!naverId.isBlank()) {
            registrations.add(naver(naverId.trim(), naverSecret.trim()));
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    /**
     * 네이버 로그인 (D-107). 스프링에 기본 등록이 없어 주소를 직접 적는다.
     * 네이버는 scope를 쓰지 않고, 이메일·별명은 네이버 개발자센터의 "제공 정보" 설정으로 정해진다.
     * 사용자 정보는 {resultcode, message, response:{id, email, nickname, ...}} 모양이라 이름 칸을 response로 둔다.
     */
    static ClientRegistration naver(String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId(SocialAccount.NAVER)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientName("Naver")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                .tokenUri("https://nid.naver.com/oauth2.0/token")
                .userInfoUri("https://openapi.naver.com/v1/nid/me")
                .userNameAttributeName("response")
                .build();
    }
}
