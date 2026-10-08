package com.blognm.mainblog.auth;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.blognm.mainblog.config.AppProperties;

/** Cloudflare Turnstile 사람 확인 (SEC-13, D-79). */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    private final AppProperties.Turnstile props;
    private final RestClient restClient;

    public TurnstileVerifier(AppProperties props) {
        this.props = props.turnstile();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public String siteKey() {
        return props.siteKey();
    }

    public boolean verify(String token, String remoteIp) {
        if (token == null || token.isBlank()) {
            return false;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", props.secretKey());
        form.add("response", token);
        form.add("remoteip", remoteIp);
        try {
            Map<?, ?> result = restClient.post()
                    .uri(props.verifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return result != null && Boolean.TRUE.equals(result.get("success"));
        } catch (Exception e) {
            log.warn("Turnstile 확인 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
