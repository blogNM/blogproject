package com.blognm.mainblog.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * 구글 로그인 앱 등록 (D-103). GOOGLE_CLIENT_ID가 비어 있으면 만들지 않고, 로그인 화면의 구글 버튼도 숨긴다.
 * 값이 없어도 서버가 뜨도록 스프링 기본 설정(spring.security.oauth2.client.*) 대신 여기서 직접 만든다.
 */
@Configuration
@ConditionalOnExpression("!'${app.social.google.client-id:}'.isBlank()")
public class SocialLoginConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.social.google.client-id}") String clientId,
            @Value("${app.social.google.client-secret:}") String clientSecret) {
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder(SocialAccount.GOOGLE)
                .clientId(clientId.trim())
                .clientSecret(clientSecret.trim())
                .scope("openid", "email", "profile")
                .build());
    }
}
