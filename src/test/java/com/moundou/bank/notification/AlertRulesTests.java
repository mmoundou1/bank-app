package com.moundou.bank.notification;

import com.moundou.bank.ledger.TransactionStatus;
import com.moundou.bank.notification.AlertRules.Decision;
import com.moundou.bank.notification.OutboxWriter.AlertKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.moundou.bank.ledger.TransactionStatus.APPROVED;
import static com.moundou.bank.ledger.TransactionStatus.CANCELLED;
import static com.moundou.bank.ledger.TransactionStatus.DECLINED;
import static com.moundou.bank.ledger.TransactionStatus.PENDING;
import static com.moundou.bank.notification.AlertRules.Decision.SEND;
import static com.moundou.bank.notification.AlertRules.Decision.SKIP;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.CANCELLATION_ALERT;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.PENDING_ALERT;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.SETTLEMENT_ALERT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. Whether the outbox worker sends an alert or marks it skipped
 * (Notify.Pending-1, Notify.Settlement-1 and -4; CR-18; Technical Design 7). This is the
 * specification for AlertRules.decide: when every test passes, the rule is right.
 *
 * The worker passes the alert's kind and the status of its transaction as read when the
 * envelope is handled, not when it was written. The envelope is a pointer; the
 * transaction is the fact. Pure code: nothing touches a database.
 */
class AlertRulesTests {

    @Nested
    class PendingAlerts {

        @Test
        void isSentWhileTheItemIsStillPending() {                              // T-NPend-1a
            assertThat(AlertRules.decide(PENDING_ALERT, PENDING)).isEqualTo(SEND);
        }

        @Test
        void isSkippedWhenTheItemWasCancelledBeforeTheWorkerGotToIt() {         // T-NSettle-4b
            assertThat(AlertRules.decide(PENDING_ALERT, CANCELLED)).isEqualTo(SKIP);
        }

        @Test
        void isSkippedWhenTheItemWasAlreadyApprovedInTheApp() {                 // CR-18
            assertThat(AlertRules.decide(PENDING_ALERT, APPROVED)).isEqualTo(SKIP);
        }

        @Test
        void isSkippedWhenTheItemWasAlreadyDeclinedInTheApp() {                 // CR-18
            assertThat(AlertRules.decide(PENDING_ALERT, DECLINED)).isEqualTo(SKIP);
        }
    }

    @Nested
    class SettlementAlerts {

        @Test
        void isSentForAnApproval() {                                            // T-NSettle-1a
            assertThat(AlertRules.decide(SETTLEMENT_ALERT, APPROVED)).isEqualTo(SEND);
        }

        @Test
        void isSentForADecline() {                                              // T-NSettle-1a
            assertThat(AlertRules.decide(SETTLEMENT_ALERT, DECLINED)).isEqualTo(SEND);
        }
    }

    @Nested
    class CancellationAlerts {

        @Test
        void isAlwaysSent() {                                                   // T-NSettle-4a, T-NSettle-4b
            assertThat(AlertRules.decide(CANCELLATION_ALERT, CANCELLED)).isEqualTo(SEND);
        }
    }

    @Nested
    class TheWholeTable {

        record Row(AlertKind kind, TransactionStatus status, Decision expected) {
        }

        /**
         * Every kind against every status, including pairs the services never write,
         * such as a settlement alert for a pending item. Those are sent: CR-18 skips only
         * pending alerts. Listing all of them means an alert kind or a status added later
         * fails here, with a row to fill in, rather than surprising the worker.
         */
        @Test
        void onlyAPendingAlertWhoseItemIsNoLongerPendingIsSkipped() {
            List<Row> table = List.of(
                    new Row(PENDING_ALERT, PENDING, SEND),
                    new Row(PENDING_ALERT, APPROVED, SKIP),
                    new Row(PENDING_ALERT, DECLINED, SKIP),
                    new Row(PENDING_ALERT, CANCELLED, SKIP),

                    new Row(SETTLEMENT_ALERT, PENDING, SEND),
                    new Row(SETTLEMENT_ALERT, APPROVED, SEND),
                    new Row(SETTLEMENT_ALERT, DECLINED, SEND),
                    new Row(SETTLEMENT_ALERT, CANCELLED, SEND),

                    new Row(CANCELLATION_ALERT, PENDING, SEND),
                    new Row(CANCELLATION_ALERT, APPROVED, SEND),
                    new Row(CANCELLATION_ALERT, DECLINED, SEND),
                    new Row(CANCELLATION_ALERT, CANCELLED, SEND));

            assertThat(table).hasSize(AlertKind.values().length * TransactionStatus.values().length);
            for (Row row : table) {
                assertThat(AlertRules.decide(row.kind(), row.status()))
                        .as(row.kind() + " / " + row.status())
                        .isEqualTo(row.expected());
            }
        }
    }
}
