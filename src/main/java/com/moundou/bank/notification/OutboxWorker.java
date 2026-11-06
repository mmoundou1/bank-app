package com.moundou.bank.notification;

import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private final NotificationRepository notifications;
    private final TransactionRepository transactions;
    private final AlertEmails emails;
    private final EmailGateway gateway;
    private final Clock clock;

    OutboxWorker(NotificationRepository notifications, TransactionRepository transactions, AlertEmails emails,
                                                                                    EmailGateway gateway, Clock clock) {
        this.notifications = notifications;
        this.transactions = transactions;
        this.emails = emails;
        this.gateway = gateway;
        this.clock = clock;

    }

    public void runOnce() {

        List<NotificationRepository.ClaimedAlert> alerts = notifications.claimDue(clock.instant(),
                                                                                        Duration.ofMinutes(10), 20);

        for(NotificationRepository.ClaimedAlert alert : alerts) {

            try {
                
                LedgerTransaction item = transactions.findById(alert.transactionId()).orElseThrow();

                switch (AlertRules.decide(alert.kind(), item.status())) {

                    case AlertRules.Decision.SEND -> {

                        gateway.send(emails.compose(alert, item));
                        notifications.markSent(alert.id(), clock.instant());

                    }
                    case AlertRules.Decision.SKIP -> {
                        notifications.markSkipped(alert.id());
                    }
                }

            }
            catch (Exception e) {

                log.warn("Alert {} failed on attempt {}: {}", alert.id(), alert.attempts(), e.getMessage());

                switch (RetryPolicy.afterFailedAttempt(alert.attempts())) {
                    case RetryPolicy.Next.RetryAfter retryAfter -> {
                        notifications.retryAt(alert.id(),
                                clock.instant().plus(retryAfter.delay()), e.getMessage());
                    }
                    case RetryPolicy.Next.GiveUp giveUp -> {
                        notifications.markFailed(alert.id(), e.getMessage());
                    }
                }

            }

        }
        
    }

}
