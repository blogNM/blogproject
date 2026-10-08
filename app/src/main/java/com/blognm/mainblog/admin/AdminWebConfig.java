package com.blognm.mainblog.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.blognm.mainblog.auth.AdminPrincipal;

/** 임시 비밀번호로 들어온 관리자는 비밀번호를 바꾸기 전에는 로그인 주소 말고는 쓰지 못한다 (추천안 #16). */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAccountService accounts;

    public AdminWebConfig(AdminAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof AdminPrincipal admin) {
                    accounts.requireReady(admin.adminId());
                }
                return true;
            }
        }).addPathPatterns("/api/admin/**").excludePathPatterns("/api/admin/auth/**");
    }
}
