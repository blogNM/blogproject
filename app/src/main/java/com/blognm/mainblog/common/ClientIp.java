package com.blognm.mainblog.common;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIp {

    private ClientIp() {
    }

    /**
     * 프록시 뒤에 둘 때는 FORWARD_HEADERS_STRATEGY=native로 X-Forwarded-For를 믿게 한다.
     * 직접 헤더를 읽지 않아 사용자가 IP를 속일 수 없다.
     */
    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
