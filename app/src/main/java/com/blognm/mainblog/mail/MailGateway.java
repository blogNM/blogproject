package com.blognm.mainblog.mail;

public interface MailGateway {

    void send(String to, String subject, String body);
}
