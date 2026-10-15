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

/** Google reCAPTCHA v2 "로봇이 아닙니다" 사람 확인 (SEC-13, D-79, D-118). */
@Component
public class CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(CaptchaVerifier.class);

    /** Google이 공개한 v2 테스트 키. 늘 통과하고 상자에 "테스트용" 경고가 뜬다. */
    static final String TEST_SITE_KEY = "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI";
    static final String TEST_SECRET_KEY = "6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe";

    private final AppProperties.Recaptcha props;
    private final RestClient restClient;

    public CaptchaVerifier(AppProperties props) {
        this.props = props.recaptcha();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        if (usingTestKeys()) {
            log.warn("reCAPTCHA 테스트 키를 씁니다. 사람 확인이 늘 통과합니다. 실제 사이트는 RECAPTCHA_SITE_KEY·RECAPTCHA_SECRET_KEY를 넣어 주세요 (D-118).");
        }
    }

    public boolean usingTestKeys() {
        return isTestKey(props.siteKey()) || isTestKey(props.secretKey());
    }

    private static boolean isTestKey(String key) {
        return key == null || key.isBlank() || TEST_SITE_KEY.equals(key) || TEST_SECRET_KEY.equals(key);
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
            if (result != null && !Boolean.TRUE.equals(result.get("success"))) {
                log.info("reCAPTCHA 확인 거절: {}", result.get("error-codes"));
            }
            return result != null && Boolean.TRUE.equals(result.get("success"));
        } catch (Exception e) {
            log.warn("reCAPTCHA 확인 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
