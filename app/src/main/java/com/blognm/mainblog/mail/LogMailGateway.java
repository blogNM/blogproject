package com.blognm.mainblog.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 개발용: 메일을 보내지 않고 서버 콘솔에 찍는다. local 프로필에서만 쓴다.
 * 인증번호가 콘솔에 보이므로 운영에서는 절대 쓰지 않는다 (SC-004).
 */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "log")
public class LogMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(LogMailGateway.class);

    private final List<SentMail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String body) {
        sent.add(new SentMail(to, subject, body));
        log.info("""

                ===== [개발용 메일, 실제로 보내지 않음] =====
                받는 사람: {}
                제목: {}
                {}
                ===========================================""", to, subject, body);
    }

    /** 테스트에서 보낸 메일을 확인할 때 쓴다. */
    public List<SentMail> sent() {
        return sent;
    }

    public record SentMail(String to, String subject, String body) {
    }
}
