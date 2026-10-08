package com.blognm.mainblog.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.blognm.mainblog.config.AppProperties;

/** Gmail SMTP로 보낸다 (D-10, D-69). */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "smtp", matchIfMissing = true)
public class SmtpMailGateway implements MailGateway {

    private final JavaMailSender sender;
    private final AppProperties props;

    public SmtpMailGateway(JavaMailSender sender, AppProperties props) {
        this.sender = sender;
        this.props = props;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.mail().from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}
