package com.blognm.mainblog.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.blognm.mainblog.config.AppProperties;

/**
 * 인증 메일. 비동기로 보내 응답 시간으로 가입 여부를 알 수 없게 한다 (SC-002).
 * 실패해도 화면 문구는 같다 (001 Assumptions).
 */
@Service
public class AuthMailService {

    private static final Logger log = LoggerFactory.getLogger(AuthMailService.class);

    private final MailGateway gateway;
    private final AppProperties props;

    public AuthMailService(MailGateway gateway, AppProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Async
    public void sendSignupCode(String email, String code, long ttlMinutes) {
        send(email, "[메인블로그] 회원가입 인증번호", """
                메인블로그 회원가입 인증번호입니다.

                인증번호: %s

                %d분 안에 입력해 주세요. 본인이 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(code, ttlMinutes));
    }

    @Async
    public void sendPasswordResetCode(String email, String code, long ttlMinutes) {
        send(email, "[메인블로그] 비밀번호 재설정 인증번호", """
                메인블로그 비밀번호 재설정 인증번호입니다.

                인증번호: %s

                %d분 안에 비밀번호 찾기 화면에 입력해 주세요. 한 번만 쓸 수 있습니다.
                본인이 요청하지 않았다면 이 메일을 무시하세요. 비밀번호는 바뀌지 않습니다.
                """.formatted(code, ttlMinutes));
    }

    /** 이미 가입된 이메일로 가입을 시도하면 인증번호 대신 이 안내를 보낸다 (D-28). */
    @Async
    public void sendAlreadyRegistered(String email) {
        send(email, "[메인블로그] 이미 가입된 이메일입니다", """
                이 이메일로 회원가입을 시도했지만, 이미 가입된 이메일입니다.

                비밀번호가 기억나지 않으면 로그인 화면의 [비밀번호 찾기]를 이용해 주세요.
                %s/login.html

                본인이 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(props.baseUrl()));
    }

    private void send(String to, String subject, String body) {
        try {
            gateway.send(to, subject, body);
        } catch (Exception e) {
            // 받는 사람 주소와 본문(인증번호)은 로그에 남기지 않는다.
            log.warn("인증 메일 발송 실패: {}", e.getClass().getSimpleName());
        }
    }
}
