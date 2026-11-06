package com.moundou.bank.notification;

public interface EmailGateway {
    void send(EmailMessage message);
}
