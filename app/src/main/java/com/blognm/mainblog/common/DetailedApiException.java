package com.blognm.mainblog.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** 문구와 함께 화면에 필요한 값(예: 사람 확인 필요 여부)을 더 보내는 예외. */
public class DetailedApiException extends ApiException {

    private final Map<String, Object> details;

    public DetailedApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(status, code, message);
        this.details = details;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
