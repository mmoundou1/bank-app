package com.moundou.bank.notification;

import com.moundou.bank.ledger.TransactionRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The notification module's beans (Technical Design 7). The timer that runs the worker
 * is OutboxSchedule, which exists only when {@code bank.notification.worker-enabled} is on.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfig {

    @Bean
    EmailGateway emailGateway(NotificationProperties properties, MessageSource messages) {
        NotificationProperties.Brevo brevo = properties.brevo();
        String senderName = messages.getMessage("app.name", null, ThymeleafAlertEmails.LOCALE);
        return new BrevoEmailGateway(brevo.url(), brevo.apiKey(), brevo.senderEmail(), senderName, brevo.timeout());
    }

    @Bean
    OutboxWorker outboxWorker(NotificationRepository notifications, TransactionRepository transactions,
                              AlertEmails emails, EmailGateway gateway, Clock clock) {
        return new OutboxWorker(notifications, transactions, emails, gateway, clock);
    }
}
