package com.moundou.bank.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

/**
 * Runs the outbox worker every 15 seconds (Technical Design 7), in the app's own process.
 *
 * Fixed delay, not fixed rate: the next visit starts 15 seconds after the last one ends,
 * so two visits never overlap, however long a slow provider makes one. If a visit fails as
 * a whole (the database unreachable when claiming), Spring logs it and the next one runs
 * on time.
 *
 * Exists only when {@code bank.notification.worker-enabled} is on: on in the app, off in
 * the tests (src/test/resources/application.properties).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "bank.notification.worker-enabled", havingValue = "true")
class OutboxSchedule {

    private final OutboxWorker worker;

    OutboxSchedule(OutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(initialDelay = 15, fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    void sendDueAlerts() {
        worker.runOnce();
    }
}
