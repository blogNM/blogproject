package com.blognm.mainblog.ops;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 응답에 어느 서버(app-a·app-b)가 답했는지 X-Instance 헤더로 붙인다 (D-111).
 * Nginx가 두 서버에 고르게 나누는지 브라우저 개발자 도구나 curl로 바로 확인할 수 있다.
 * 값은 컨테이너마다 넘기는 INSTANCE_ID다. 비어 있으면 붙이지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Instance";

    private final String instanceId;

    public InstanceHeaderFilter(@Value("${app.instance-id:}") String instanceId) {
        this.instanceId = instanceId == null ? "" : instanceId.trim();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!instanceId.isEmpty()) {
            response.setHeader(HEADER, instanceId);
        }
        chain.doFilter(request, response);
    }
}
