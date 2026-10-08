package com.blognm.mainblog.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Map<String, Object> details) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
