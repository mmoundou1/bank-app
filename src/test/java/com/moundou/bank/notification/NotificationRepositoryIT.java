package com.moundou.bank.notification;

import com.moundou.bank.notification.NotificationRepository.ClaimedAlert;
import com.moundou.bank.notification.OutboxWriter.AlertKind;
import com.moundou.bank.support.LedgerFixtures;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.moundou.bank.notification.OutboxWriter.AlertKind.CANCELLATION_ALERT;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.PENDING_ALERT;
import static com.moundou.bank.notification.OutboxWriter.AlertKind.SETTLEMENT_ALERT;
import static com.moundou.bank.support.LedgerFixtures.USD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The outbox worker's SQL (Technical Design 7, D5): claiming due alerts with a
 * reservation, and recording what happened to each. This is the specification for
 * NotificationRepository.
 *
 * Time is passed in, so every test uses the fixed instant NOW rather than the clock. Rows
 * are written directly, with the state and due time each test needs.
 */
class NotificationRepositoryIT {

    static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");
    static final Duration RESERVATION = Duration.ofMinutes(10);

    private static JdbcClient jdbc;
    private NotificationRepository notifications;
    private UUID item;
    private UUID ben;

    @BeforeAll
    static void migrate() {
        TestDatabase.migrateFromEmpty();
        jdbc = JdbcClient.create(TestDatabase.dataSource());
    }

    @BeforeEach
    void oneItem() {
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        LedgerFixtures ledger = new LedgerFixtures(jdbc);
        UUID ama = ledger.member("Ama");
        ben = ledger.member("Ben");
        item = ledger.loan(ama, ben, 2000, USD);
        notifications = new NotificationRepository(jdbc);
    }

    @Nested
    class Claiming {

        @Test
        void claimsOnlyPendingAlertsThatAreDue() {
            UUID due = alert(PENDING_ALERT, "pending", NOW.minusSeconds(1));
            UUID dueThisInstant = alert(PENDING_ALERT, "pending", NOW);
            alert(PENDING_ALERT, "pending", NOW.plusSeconds(30));      // waiting for its next try
            alert(PENDING_ALERT, "sent", NOW.minusSeconds(60));
            alert(PENDING_ALERT, "skipped", NOW.minusSeconds(60));
            alert(PENDING_ALERT, "failed", NOW.minusSeconds(60));

            assertThat(ids(notifications.claimDue(NOW, RESERVATION, 20)))
                    .containsExactlyInAnyOrder(due, dueThisInstant);
        }

        @Test
        void addsOneAttemptAndReservesTheAlert() {
            UUID id = alert(PENDING_ALERT, "pending", NOW);

            List<ClaimedAlert> claimed = notifications.claimDue(NOW, RESERVATION, 20);

            assertThat(claimed).singleElement().satisfies(a -> {
                assertThat(a.id()).isEqualTo(id);
                assertThat(a.transactionId()).isEqualTo(item);
                assertThat(a.recipientId()).isEqualTo(ben);
                assertThat(a.kind()).isEqualTo(PENDING_ALERT);
                assertThat(a.attempts()).isEqualTo(1);
            });
            assertThat(nextAttemptAt(id)).isEqualTo(NOW.plus(RESERVATION));
            assertThat(state(id)).isEqualTo("pending");
        }

        @Test
        void aReservedAlertIsNotClaimedAgainUntilTheReservationRunsOut() {
            UUID id = alert(PENDING_ALERT, "pending", NOW);
            notifications.claimDue(NOW, RESERVATION, 20);

            assertThat(notifications.claimDue(NOW.plus(RESERVATION).minusSeconds(1), RESERVATION, 20)).isEmpty();

            // Nothing was recorded, as after a crash mid-send: the alert comes back, never lost.
            List<ClaimedAlert> again = notifications.claimDue(NOW.plus(RESERVATION), RESERVATION, 20);
            assertThat(again).singleElement().satisfies(a -> {
                assertThat(a.id()).isEqualTo(id);
                assertThat(a.attempts()).isEqualTo(2);
            });
        }

        @Test
        void claimsAtMostTheLimitOldestFirst() {
            UUID oldest = alert(PENDING_ALERT, "pending", NOW.minusSeconds(300));
            UUID middle = alert(PENDING_ALERT, "pending", NOW.minusSeconds(200));
            UUID newest = alert(PENDING_ALERT, "pending", NOW.minusSeconds(100));

            assertThat(ids(notifications.claimDue(NOW, RESERVATION, 2))).containsExactlyInAnyOrder(oldest, middle);
            assertThat(ids(notifications.claimDue(NOW, RESERVATION, 2))).containsExactly(newest);
        }

        @Test
        void readsBackEveryAlertKind() {
            for (AlertKind kind : AlertKind.values()) {
                alert(kind, "pending", NOW);
            }

            assertThat(notifications.claimDue(NOW, RESERVATION, 20))
                    .extracting(ClaimedAlert::kind)
                    .containsExactlyInAnyOrder(PENDING_ALERT, SETTLEMENT_ALERT, CANCELLATION_ALERT);
        }

        @Test
        void workersClaimingAtTheSameMomentNeverClaimTheSameAlert() throws Exception {
            // Four workers race to empty the same tray, 20 rounds. SKIP LOCKED: each alert is
            // claimed by exactly one of them, and none waits for another's rows.
            int workers = 4;
            int alertsPerRound = 30;
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                for (int round = 0; round < 20; round++) {
                    jdbc.sql("TRUNCATE notification").update();
                    List<UUID> written = new ArrayList<>();
                    for (int i = 0; i < alertsPerRound; i++) {
                        written.add(alert(PENDING_ALERT, "pending", NOW));
                    }

                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<List<UUID>>> results = new ArrayList<>();
                    for (int w = 0; w < workers; w++) {
                        results.add(pool.submit(() -> {
                            start.await();
                            // Capped: each non-empty claim takes at least one alert, so a correct
                            // worker never needs more claims than there are alerts. Without the
                            // cap, a claim that forgets to reserve would loop here forever
                            // instead of failing the duplicate check below.
                            List<UUID> mine = new ArrayList<>();
                            for (int claims = 0; claims < alertsPerRound; claims++) {
                                List<ClaimedAlert> batch = notifications.claimDue(NOW, RESERVATION, 4);
                                if (batch.isEmpty()) {
                                    break;
                                }
                                mine.addAll(ids(batch));
                            }
                            return mine;
                        }));
                    }
                    start.countDown();

                    List<UUID> claimed = new ArrayList<>();
                    for (Future<List<UUID>> result : results) {
                        claimed.addAll(result.get());
                    }
                    assertThat(claimed).as("round " + round).doesNotHaveDuplicates()
                            .containsExactlyInAnyOrderElementsOf(written);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    class Recording {

        UUID id;

        @BeforeEach
        void oneClaimedAlert() {
            id = alert(PENDING_ALERT, "pending", NOW);
            notifications.claimDue(NOW, RESERVATION, 20);
        }

        @Test
        void aSentAlertIsDoneAndRemembersWhen() {
            assertThat(notifications.markSent(id, NOW.plusSeconds(2))).isTrue();

            assertThat(state(id)).isEqualTo("sent");
            assertThat(instant("sent_at", id)).isEqualTo(NOW.plusSeconds(2));
            assertThat(notifications.claimDue(NOW.plus(Duration.ofDays(30)), RESERVATION, 20)).isEmpty();
        }

        @Test
        void aSkippedAlertIsDoneAndNothingWasSent() {                            // T-NSettle-4b
            assertThat(notifications.markSkipped(id)).isTrue();

            assertThat(state(id)).isEqualTo("skipped");
            assertThat(instant("sent_at", id)).isNull();
            assertThat(notifications.claimDue(NOW.plus(Duration.ofDays(30)), RESERVATION, 20)).isEmpty();
        }

        @Test
        void aFailedSendThatWillBeRetriedStaysPendingUntilItsNextTry() {
            Instant nextTry = NOW.plus(Duration.ofMinutes(1));
            assertThat(notifications.retryAt(id, nextTry, "provider returned 503")).isTrue();

            assertThat(state(id)).isEqualTo("pending");
            assertThat(nextAttemptAt(id)).isEqualTo(nextTry);
            assertThat(lastError(id)).isEqualTo("provider returned 503");
            assertThat(notifications.claimDue(nextTry.minusSeconds(1), RESERVATION, 20)).isEmpty();
            assertThat(ids(notifications.claimDue(nextTry, RESERVATION, 20))).containsExactly(id);
        }

        @Test
        void aFailedSendWithNoTriesLeftIsDoneAndKeepsTheError() {
            assertThat(notifications.markFailed(id, "provider returned 503")).isTrue();

            assertThat(state(id)).isEqualTo("failed");
            assertThat(lastError(id)).isEqualTo("provider returned 503");
            assertThat(notifications.claimDue(NOW.plus(Duration.ofDays(30)), RESERVATION, 20)).isEmpty();
        }

        @Test
        void aSuccessfulSendClearsTheErrorOfAnEarlierTry() {
            notifications.retryAt(id, NOW, "timed out");

            notifications.markSent(id, NOW);

            assertThat(lastError(id)).isNull();
        }

        @Test
        void anAlertThatIsAlreadyDoneIsNeverRecordedAgain() {
            notifications.markSkipped(id);

            assertThat(notifications.markSent(id, NOW)).isFalse();
            assertThat(notifications.markFailed(id, "late")).isFalse();
            assertThat(notifications.retryAt(id, NOW, "late")).isFalse();
            assertThat(notifications.markSkipped(id)).isFalse();
            assertThat(state(id)).isEqualTo("skipped");
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private UUID alert(AlertKind kind, String state, Instant nextAttemptAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO notification (notification_id, transaction_id, recipient_id, kind, state,
                                                  next_attempt_at, sent_at)
                        VALUES (:id, :item, :ben, :kind, :state, :next,
                                CASE WHEN :state = 'sent' THEN :next END)""")
                .param("id", id)
                .param("item", item)
                .param("ben", ben)
                .param("kind", kind.name().toLowerCase())
                .param("state", state)
                .param("next", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
                .update();
        return id;
    }

    private static List<UUID> ids(List<ClaimedAlert> alerts) {
        return alerts.stream().map(ClaimedAlert::id).toList();
    }

    private String state(UUID id) {
        return jdbc.sql("SELECT state FROM notification WHERE notification_id = :id")
                .param("id", id).query(String.class).single();
    }

    private String lastError(UUID id) {
        return jdbc.sql("SELECT last_error FROM notification WHERE notification_id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    private Instant nextAttemptAt(UUID id) {
        return instant("next_attempt_at", id);
    }

    private Instant instant(String column, UUID id) {
        OffsetDateTime value = jdbc.sql("SELECT " + column + " FROM notification WHERE notification_id = :id")
                .param("id", id)
                .query((rs, row) -> rs.getObject(1, OffsetDateTime.class))
                .single();
        return value == null ? null : value.toInstant();
    }
}
