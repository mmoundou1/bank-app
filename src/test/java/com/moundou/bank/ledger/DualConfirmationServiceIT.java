package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.ledger.DualConfirmationService.Outcome;
import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.MutableClock;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slow suite. MB-12's first PR through the real Spring context: the queue on Home,
 * viewing one item, declining and cancelling, with their transaction, history rows and
 * alerts. Test IDs are from the Test Derivation page. Approving comes in later PRs.
 *
 * Ama sends, Ben decides, Cleo is a member who is party to nothing here.
 */
@SpringBootTest(properties = "bank.ledger.onboarding-ends-on=2026-10-31")
class DualConfirmationServiceIT {

    static final Instant NOW = Instant.parse("2026-10-08T16:00:00Z");

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.migrateFromEmpty();
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired DualConfirmationService service;
    @Autowired MutableClock clock;

    JdbcClient jdbc;
    LedgerFixtures ledger;
    UUID ama;
    UUID ben;
    UUID cleo;
    /** A pending $20.00 loan Ama recorded lending to Ben. */
    UUID loan;

    @BeforeEach
    void freshLedger() {
        clock.set(NOW);
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        ama = ledger.member("Ama");
        ben = ledger.member("Ben");
        cleo = ledger.member("Cleo");
        loan = ledger.loan(ama, ben, 2000, USD);
    }

    // ---- helpers ------------------------------------------------------------------

    List<List<Object>> history(UUID transactionId) {
        return jdbc.sql("""
                SELECT from_status, to_status, actor_id FROM transaction_status_history
                WHERE transaction_id = :t AND from_status IS NOT NULL ORDER BY occurred_at""")
                .param("t", transactionId)
                .query((rs, n) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)))
                .list();
    }

    List<List<Object>> alerts(UUID transactionId) {
        return jdbc.sql("SELECT recipient_id, kind FROM notification WHERE transaction_id = :t ORDER BY created_at")
                .param("t", transactionId)
                .query((rs, n) -> List.<Object>of(rs.getObject(1, UUID.class), rs.getString(2)))
                .list();
    }

    String declineReason(UUID transactionId) {
        return jdbc.sql("SELECT decline_reason FROM transaction WHERE transaction_id = :t")
                .param("t", transactionId).query(String.class).single();
    }

    void deactivate(UUID member) {
        jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :m").param("m", member).update();
    }

    // ---- the queue ----------------------------------------------------------------

    @Nested
    class Queue {

        @Test
        void eachMemberSeesWhatTheyDecideAndWhatTheySentWithEveryNameTheRowsMention() {   // T-Approval-1a
            UUID fromCleo = ledger.loan(cleo, ama, 500, USD);        // Ama decides

            var queue = service.queue(ama);

            assertThat(queue.toDecide()).extracting(LedgerTransaction::id).containsExactly(fromCleo);
            assertThat(queue.waiting()).extracting(LedgerTransaction::id).containsExactly(loan);
            assertThat(queue.names()).containsEntry(ama, "Ama").containsEntry(ben, "Ben").containsEntry(cleo, "Cleo");
        }

        @Test
        void anEmptyQueueHasNoNames() {
            var queue = service.queue(cleo);

            assertThat(queue.toDecide()).isEmpty();
            assertThat(queue.waiting()).isEmpty();
            assertThat(queue.names()).isEmpty();
        }

        @Test
        void aDeactivatedMemberGetsNoQueue() {                                    // Auth.Login-5
            deactivate(ama);

            assertThatThrownBy(() -> service.queue(ama)).isInstanceOf(NotPermittedException.class);
        }
    }

    // ---- one item -----------------------------------------------------------------

    @Nested
    class View {

        @Test
        void bothPartiesMayOpenTheItemAndSeeBothNames() {                         // T-Approval-2a, Notify.Pending-2
            assertThat(service.view(ben, loan).transaction().id()).isEqualTo(loan);
            assertThat(service.view(ama, loan).partiesMap())
                    .containsOnlyKeys(ama, ben).containsEntry(ama, "Ama").containsEntry(ben, "Ben");
        }

        @Test
        void aMemberWhoIsNotAPartyIsRefused() {                                  // Ledger.History-7, SEC-2
            assertThatThrownBy(() -> service.view(cleo, loan)).isInstanceOf(NotPermittedException.class);
        }

        @Test
        void aDecidedItemCanStillBeOpened() {                                    // the emailed link still works
            service.decline(ben, loan, null);

            assertThat(service.view(ben, loan).transaction().status()).isEqualTo(TransactionStatus.DECLINED);
        }
    }

    // ---- declining ----------------------------------------------------------------

    @Nested
    class Decline {

        @Test
        void theCounterpartyDeclinesWithAReasonAndTheInitiatorIsAlerted() {     // T-Approval-7a, T-Approval-10a, T-NSettle-1a
            Outcome outcome = service.decline(ben, loan, "I paid cash that day");

            assertThat(outcome).isInstanceOf(Outcome.Recorded.class);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.DECLINED);
            assertThat(declineReason(loan)).isEqualTo("I paid cash that day");
            assertThat(history(loan)).containsExactly(List.of("pending", "declined", ben));
            assertThat(alerts(loan)).contains(List.of(ama, "settlement_alert"));
            assertThat(service.queue(ben).toDecide()).isEmpty();
            assertThat(service.queue(ama).waiting()).isEmpty();
        }

        @Test
        void aBlankReasonIsStoredAsNoReason() {                                  // T-NSettle-2a
            service.decline(ben, loan, "   ");

            assertThat(declineReason(loan)).isNull();
        }

        @Test
        void theInitiatorCannotDeclineTheirOwnItem() {                           // T-Approval-3a
            assertThatThrownBy(() -> service.decline(ama, loan, null)).isInstanceOf(NotPermittedException.class);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void aMemberWhoIsNotAPartyCannotDecline() {                              // T-Approval-3b
            assertThatThrownBy(() -> service.decline(cleo, loan, null)).isInstanceOf(NotPermittedException.class);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void anUnknownItemIsRefusedNotAnError() {
            assertThatThrownBy(() -> service.decline(ben, UUID.randomUUID(), null)).isInstanceOf(NotPermittedException.class);
        }

        @Test
        void decliningTwiceRecordsOneDecision() {                              // T-Approval-9a
            service.decline(ben, loan, "no");

            Outcome second = service.decline(ben, loan, "still no");

            assertThat(second).isEqualTo(new Outcome.Rejected("ledger.approval.notPending"));
            assertThat(declineReason(loan)).isEqualTo("no");
            assertThat(history(loan)).hasSize(1);
            assertThat(alerts(loan)).filteredOn(a -> a.get(1).equals("settlement_alert")).hasSize(1);
        }

        @Test
        void aDeactivatedCounterpartyCannotDecline() {                          // Auth.Login-5
            deactivate(ben);

            assertThatThrownBy(() -> service.decline(ben, loan, null)).isInstanceOf(NotPermittedException.class);
        }
    }

    // ---- cancelling ---------------------------------------------------------------

    @Nested
    class Cancel {

        @Test
        void theInitiatorCancelsAndTheItemLeavesBothQueues() {                  // T-Approval-11a, T-Approval-13a
            Outcome outcome = service.cancel(ama, loan);

            assertThat(outcome).isInstanceOf(Outcome.Recorded.class);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.CANCELLED);
            assertThat(history(loan)).containsExactly(List.of("pending", "cancelled", ama));
            assertThat(service.queue(ben).toDecide()).isEmpty();
            assertThat(service.queue(ama).waiting()).isEmpty();
        }

        @Test
        void theRecordIsKeptNotDeleted() {                                      // Data-8, Data-2
            service.cancel(ama, loan);

            assertThat(service.view(ben, loan).transaction().status()).isEqualTo(TransactionStatus.CANCELLED);
        }

        @Test
        void noAlertIsSentForACancellation() {                                  // SRS 1.12; CR-18 (MB-40) proposes one
            service.cancel(ama, loan);

            assertThat(alerts(loan)).noneMatch(a -> a.get(1).equals("settlement_alert"));
        }

        @Test
        void theCounterpartyCannotCancel() {                                    // T-Approval-12a, first half
            assertThatThrownBy(() -> service.cancel(ben, loan)).isInstanceOf(NotPermittedException.class);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void aMemberWhoIsNotAPartyCannotCancel() {
            assertThatThrownBy(() -> service.cancel(cleo, loan)).isInstanceOf(NotPermittedException.class);
        }

        @Test
        void aDecidedItemCannotBeCancelled() {                                  // T-Approval-12a, second half
            UUID approved = ledger.approvedLoan(ama, ben, 700, USD);
            service.decline(ben, loan, null);

            assertThat(service.cancel(ama, approved)).isEqualTo(new Outcome.Rejected("ledger.approval.notPending"));
            assertThat(service.cancel(ama, loan)).isEqualTo(new Outcome.Rejected("ledger.approval.notPending"));
            assertThat(ledger.status(approved)).isEqualTo(TransactionStatus.APPROVED);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.DECLINED);
        }

        @Test
        void thereIsNoTimeLimit() {                                             // T-Approval-14a
            clock.set(NOW.plusSeconds(60L * 24 * 3600));

            assertThat(service.queue(ben).toDecide()).extracting(LedgerTransaction::id).containsExactly(loan);
            assertThat(service.cancel(ama, loan)).isInstanceOf(Outcome.Recorded.class);
        }
    }

    // ---- the race between the two parties -----------------------------------------

    /**
     * Ama cancels while Ben declines, at the same moment, 50 times. The row lock means
     * exactly one of them wins every time, and the other is told "no longer pending"
     * rather than overwriting the first decision (Technical Design 4).
     */
    @Test
    void cancelAndDeclineAtTheSameMomentExactlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                UUID item = ledger.loan(ama, ben, 100 + round, USD);
                CountDownLatch start = new CountDownLatch(1);
                Callable<Outcome> cancel = () -> { start.await(); return service.cancel(ama, item); };
                Callable<Outcome> decline = () -> { start.await(); return service.decline(ben, item, null); };
                Future<Outcome> a = pool.submit(cancel);
                Future<Outcome> b = pool.submit(decline);
                start.countDown();

                List<Outcome> outcomes = new ArrayList<>(List.of(a.get(), b.get()));
                assertThat(outcomes).as("round %d", round)
                        .filteredOn(o -> o instanceof Outcome.Recorded).hasSize(1);
                assertThat(outcomes).as("round %d", round)
                        .contains(new Outcome.Rejected("ledger.approval.notPending"));
                assertThat(history(item)).as("round %d: one decision recorded", round).hasSize(1);
                assertThat(ledger.status(item)).isIn(TransactionStatus.CANCELLED, TransactionStatus.DECLINED);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
