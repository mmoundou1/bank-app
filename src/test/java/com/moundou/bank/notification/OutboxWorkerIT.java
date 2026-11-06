package com.moundou.bank.notification;

import com.moundou.bank.notification.NotificationRepository.ClaimedAlert;
import com.moundou.bank.notification.OutboxWriter.AlertKind;
import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.MutableClock;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.moundou.bank.notification.OutboxWriter.AlertKind.CANCELLATION_ALERT;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.PENDING_ALERT;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.SETTLEMENT_ALERT;
import static com.moundou.bank.support.LedgerFixtures.USD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. One visit of the outbox worker (Technical Design 7): claim the due alerts,
 * then for each one read its transaction, decide with AlertRules, compose and send it, and
 * record the outcome, using RetryPolicy when the send fails. This is the specification for
 * OutboxWorker.runOnce.
 *
 * Real database, real NotificationRepository and TransactionRepository. The email side is
 * two stand-ins: a gateway that records what it was asked to send and fails on demand, and
 * a composer that writes a message from the alert alone, addressed to
 * {@code <recipient id>@test}, so each test can tell who an email was for and what it was
 * about. The clock only moves when a test moves it.
 *
 * Ama lends to Ben throughout: Ama is the initiator, Ben the counterparty.
 */
class OutboxWorkerIT {

    private static JdbcClient jdbc;

    private LedgerFixtures ledger;
    private JdbcOutboxWriter outbox;
    private MutableClock clock;
    private Instant start;
    private FakeGateway gateway;
    private OutboxWorker worker;
    private UUID ama;
    private UUID ben;

    @BeforeAll
    static void migrate() {
        TestDatabase.migrateFromEmpty();
        jdbc = JdbcClient.create(TestDatabase.dataSource());
    }

    @BeforeEach
    void emptyTray() {
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        ama = ledger.member("Ama");
        ben = ledger.member("Ben");
        outbox = new JdbcOutboxWriter(jdbc);

        // A new alert is due from the database's now(), so the worker's clock starts just
        // after it. Whole seconds, so the times the worker writes compare exactly.
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        clock = new MutableClock(start);
        gateway = new FakeGateway();
        worker = workerComposingWith(OutboxWorkerIT::addressedToRecipient);
    }

    @Nested
    class Sending {

        @Test
        void aPendingAlertIsSentToTheCounterpartyWhileTheItemIsStillPending() {   // T-NPend-1a
            UUID loan = ledger.loan(ama, ben, 2000, USD);
            UUID alert = enqueue(loan, ben, PENDING_ALERT);

            worker.runOnce();

            assertThat(gateway.sent).singleElement().satisfies(email -> {
                assertThat(email.to()).isEqualTo(ben + "@test");
                assertThat(email.subject()).isEqualTo("pending_alert about " + loan);
            });
            assertThat(state(alert)).isEqualTo("sent");
        }

        @Test
        void aSettlementAlertIsSentToTheInitiator() {                            // T-NSettle-1a
            UUID loan = ledger.approve(ledger.loan(ama, ben, 2000, USD));
            UUID alert = enqueue(loan, ama, SETTLEMENT_ALERT);

            worker.runOnce();

            assertThat(gateway.sent).singleElement()
                    .satisfies(email -> assertThat(email.to()).isEqualTo(ama + "@test"));
            assertThat(state(alert)).isEqualTo("sent");
        }

        @Test
        void theComposerIsGivenTheTransactionAsItIsWhenTheAlertIsHandled() {
            // Written while the loan was pending; approved before the worker got to it.
            UUID loan = ledger.loan(ama, ben, 2000, USD);
            enqueue(loan, ama, SETTLEMENT_ALERT);
            ledger.approve(loan);

            worker.runOnce();

            assertThat(gateway.sent).singleElement()
                    .satisfies(email -> assertThat(email.body()).isEqualTo("status APPROVED"));
        }

        @Test
        void anAlertThatIsNotDueYetIsLeftAlone() {
            UUID alert = enqueue(ledger.loan(ama, ben, 2000, USD), ben, PENDING_ALERT);
            jdbc.sql("UPDATE notification SET next_attempt_at = :later")
                    .param("later", utc(start.plusSeconds(30))).update();

            worker.runOnce();

            assertThat(gateway.tried).isEmpty();
            assertThat(state(alert)).isEqualTo("pending");
            assertThat(attempts(alert)).isZero();
        }
    }

    @Nested
    class Skipping {                                                             // CR-18

        @Test
        void aPendingAlertWhoseItemWasCancelledIsSkippedAndOnlyTheCancellationIsSent() { // T-NSettle-4b
            UUID loan = ledger.loan(ama, ben, 2000, USD);
            UUID pendingAlert = enqueue(loan, ben, PENDING_ALERT);
            ledger.cancel(loan);
            UUID cancellationAlert = enqueue(loan, ben, CANCELLATION_ALERT);

            worker.runOnce();

            assertThat(gateway.tried).singleElement()
                    .satisfies(email -> assertThat(email.subject()).isEqualTo("cancellation_alert about " + loan));
            assertThat(state(pendingAlert)).isEqualTo("skipped");
            assertThat(state(cancellationAlert)).isEqualTo("sent");
        }

        @Test
        void aPendingAlertWhoseItemWasAlreadyApprovedInTheAppIsSkipped() {
            UUID loan = ledger.loan(ama, ben, 2000, USD);
            UUID alert = enqueue(loan, ben, PENDING_ALERT);
            ledger.approve(loan);

            worker.runOnce();

            assertThat(gateway.tried).isEmpty();
            assertThat(state(alert)).isEqualTo("skipped");
        }
    }

    @Nested
    class Failing {

        @Test
        void aFailedSendIsTriedAgainAfterOneMinute() {
            UUID alert = enqueue(ledger.loan(ama, ben, 2000, USD), ben, PENDING_ALERT);
            gateway.failNext(1);

            worker.runOnce();
            assertThat(state(alert)).isEqualTo("pending");
            assertThat(lastError(alert)).contains("provider said no");

            clock.set(start.plus(Duration.ofMinutes(1)).minusSeconds(1));
            worker.runOnce();
            assertThat(gateway.tried).as("not before its minute is up").hasSize(1);

            clock.set(start.plus(Duration.ofMinutes(1)));
            worker.runOnce();
            assertThat(gateway.tried).hasSize(2);
            assertThat(state(alert)).isEqualTo("sent");
        }

        @Test
        void afterTheFifthFailedSendTheAlertIsMarkedFailedAndNeverTriedAgain() {
            UUID alert = enqueue(ledger.loan(ama, ben, 2000, USD), ben, PENDING_ALERT);
            gateway.failAlways();

            // A day between visits: longer than any wait and than the reservation.
            for (int visit = 0; visit < 10; visit++) {
                clock.set(start.plus(Duration.ofDays(visit)));
                worker.runOnce();
            }

            assertThat(gateway.tried).hasSize(5);
            assertThat(state(alert)).isEqualTo("failed");
            assertThat(lastError(alert)).contains("provider said no");
        }

        @Test
        void oneAlertFailingDoesNotStopTheOthersInTheSameVisit() {
            UUID loan = ledger.loan(ama, ben, 2000, USD);
            UUID toBen = enqueue(loan, ben, PENDING_ALERT);
            UUID toAma = enqueue(ledger.approve(ledger.loan(ama, ben, 500, USD)), ama, SETTLEMENT_ALERT);
            gateway.failWhen(email -> email.to().equals(ben + "@test"));

            worker.runOnce();

            assertThat(state(toBen)).isEqualTo("pending");
            assertThat(lastError(toBen)).contains("provider said no");
            assertThat(state(toAma)).isEqualTo("sent");
        }

        @Test
        void aMessageThatCannotBeComposedCountsAsAFailedTry() {
            UUID loan = ledger.loan(ama, ben, 2000, USD);
            UUID toBen = enqueue(loan, ben, PENDING_ALERT);
            UUID toAma = enqueue(ledger.approve(ledger.loan(ama, ben, 500, USD)), ama, SETTLEMENT_ALERT);
            worker = workerComposingWith((alert, transaction) -> {
                if (alert.recipientId().equals(ben)) {
                    throw new IllegalStateException("template broken");
                }
                return addressedToRecipient(alert, transaction);
            });

            worker.runOnce();

            assertThat(state(toBen)).isEqualTo("pending");
            assertThat(lastError(toBen)).contains("template broken");
            assertThat(state(toAma)).isEqualTo("sent");
        }
    }

    @Nested
    class Claiming {                                                             // shape B

        @Test
        void whileItsEmailIsBeingSentAnAlertIsClaimedReservedAndStillPending() {
            UUID alert = enqueue(ledger.loan(ama, ben, 2000, USD), ben, PENDING_ALERT);
            List<String> seenDuringTheSend = new ArrayList<>();
            gateway.duringSend(email -> {
                // Read on another connection. If the claim were not committed yet, this
                // would see 0 attempts and the old due time. If the worker wrote "sent"
                // before sending, a crash now would lose the email: nothing may say done
                // until the email has gone.
                seenDuringTheSend.add(state(alert) + ", " + attempts(alert) + " attempt, due again at "
                        + nextAttemptAt(alert));
            });

            worker.runOnce();

            assertThat(seenDuringTheSend)
                    .containsExactly("pending, 1 attempt, due again at " + start.plus(Duration.ofMinutes(10)));
            assertThat(state(alert)).isEqualTo("sent");
        }

        @Test
        void aVisitHandlesAtMostTwentyAlerts() {
            UUID loan = ledger.loan(ama, ben, 2000, USD);
            for (int i = 0; i < 25; i++) {
                enqueue(loan, ben, PENDING_ALERT);
            }

            worker.runOnce();
            assertThat(gateway.sent).hasSize(20);

            worker.runOnce();
            assertThat(gateway.sent).hasSize(25);
        }
    }

    // ---- the email stand-ins -------------------------------------------------------------

    /** Records every message it is asked to send, and fails when told to. */
    static class FakeGateway implements EmailGateway {

        final List<EmailMessage> tried = new ArrayList<>();
        final List<EmailMessage> sent = new ArrayList<>();
        private Predicate<EmailMessage> fails = email -> false;
        private Consumer<EmailMessage> duringSend = email -> { };
        private int failuresLeft;

        void failNext(int times) {
            failuresLeft = times;
        }

        void failAlways() {
            fails = email -> true;
        }

        void failWhen(Predicate<EmailMessage> condition) {
            fails = condition;
        }

        void duringSend(Consumer<EmailMessage> check) {
            duringSend = check;
        }

        @Override
        public void send(EmailMessage email) {
            tried.add(email);
            duringSend.accept(email);
            if (failuresLeft > 0 || fails.test(email)) {
                failuresLeft = Math.max(0, failuresLeft - 1);
                throw new IllegalStateException("provider said no");
            }
            sent.add(email);
        }
    }

    private static EmailMessage addressedToRecipient(ClaimedAlert alert, com.moundou.bank.ledger.LedgerTransaction transaction) {
        return new EmailMessage(
                alert.recipientId() + "@test",
                alert.kind().name().toLowerCase() + " about " + transaction.id(),
                "status " + transaction.status());
    }

    private OutboxWorker workerComposingWith(AlertEmails emails) {
        return new OutboxWorker(new NotificationRepository(jdbc), ledger.repository(), emails, gateway, clock);
    }

    // ---- helpers -----------------------------------------------------------------------

    private UUID enqueue(UUID transaction, UUID recipient, AlertKind kind) {
        outbox.enqueue(transaction, recipient, kind);
        return jdbc.sql("""
                        SELECT notification_id FROM notification
                         WHERE transaction_id = :transaction AND recipient_id = :recipient AND kind = :kind
                         ORDER BY created_at DESC, notification_id LIMIT 1""")
                .param("transaction", transaction)
                .param("recipient", recipient)
                .param("kind", kind.name().toLowerCase())
                .query(UUID.class).single();
    }

    private static String state(UUID id) {
        return jdbc.sql("SELECT state FROM notification WHERE notification_id = :id")
                .param("id", id).query(String.class).single();
    }

    private static int attempts(UUID id) {
        return jdbc.sql("SELECT attempts FROM notification WHERE notification_id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private static String lastError(UUID id) {
        return jdbc.sql("SELECT coalesce(last_error, '') FROM notification WHERE notification_id = :id")
                .param("id", id).query(String.class).single();
    }

    private static Instant nextAttemptAt(UUID id) {
        return jdbc.sql("SELECT next_attempt_at FROM notification WHERE notification_id = :id")
                .param("id", id)
                .query((rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
