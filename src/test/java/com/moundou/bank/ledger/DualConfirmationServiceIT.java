package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.notification.OutboxWriter;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Slow suite. MB-12's first PR through the real Spring context: the queue on Home,
 * viewing one item, declining and cancelling, with their transaction, history rows and
 * alerts. Test IDs are from the Test Derivation page. Approving covers loans,
 * repayments and corrections (MB-12 PRs 2 to 4).
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
    /** The real outbox writer, which a test can make fail partway through (T-Approval-5a, ADR-009). */
    @SpyBean OutboxWriter outbox;

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
        reset(outbox);   // back to the real writer: a failure set up by one test must not leak into the next
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

    /** A member's net position in US dollars, from the balance view (Dashboard.Net-1). */
    long net(UUID member) {
        return jdbc.sql("SELECT coalesce(sum(net_minor), 0) FROM member_net WHERE member_id = :m AND currency = 'USD'")
                .param("m", member).query(Long.class).single();
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
        void theCounterpartyAndOnlyTheCounterpartyIsAlerted() {                 // T-NSettle-4a, Notify.Settlement-4 (CR-18)
            service.cancel(ama, loan);

            assertThat(alerts(loan)).containsExactly(List.of(ben, "cancellation_alert"));
            assertThat(net(ama)).isZero();
            assertThat(net(ben)).isZero();
        }

        @Test
        void aRefusedCancelSendsNoAlert() {
            service.decline(ben, loan, null);
            int before = alerts(loan).size();

            service.cancel(ama, loan);

            assertThat(alerts(loan)).hasSize(before).noneMatch(a -> a.get(1).equals("cancellation_alert"));
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

    // ---- approving a loan ---------------------------------------------------------

    @Nested
    class Approve {

        @Test
        void theCounterpartyApprovesAndBothPositionsMoveByTheAmount() {         // T-Approval-4a
            Outcome outcome = service.approve(ben, loan);

            assertThat(outcome).isInstanceOf(Outcome.Recorded.class);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.APPROVED);
            assertThat(net(ama)).isEqualTo(2000);    // Ama lent $20.00: she is owed
            assertThat(net(ben)).isEqualTo(-2000);   // Ben owes it
            assertThat(history(loan)).containsExactly(List.of("pending", "approved", ben));
            assertThat(service.queue(ben).toDecide()).isEmpty();
            assertThat(service.queue(ama).waiting()).isEmpty();
        }

        @Test
        void theInitiatorIsAlerted() {                                          // T-Approval-10a, T-NSettle-1a
            service.approve(ben, loan);

            assertThat(alerts(loan)).contains(List.of(ama, "settlement_alert"));
        }

        @Test
        void theInitiatorCannotApproveTheirOwnItem() {                          // T-Approval-3a
            assertThatThrownBy(() -> service.approve(ama, loan)).isInstanceOf(NotPermittedException.class);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.PENDING);
            assertThat(net(ama)).isZero();
        }

        @Test
        void aMemberWhoIsNotAPartyCannotApprove() {                             // T-Approval-3b
            assertThatThrownBy(() -> service.approve(cleo, loan)).isInstanceOf(NotPermittedException.class);
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void aDeclinedItemCannotBeApproved() {                                  // T-Approval-9a
            service.decline(ben, loan, null);

            assertThat(service.approve(ben, loan)).isEqualTo(new Outcome.Rejected("ledger.approval.notPending"));
            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.DECLINED);
            assertThat(net(ben)).isZero();
        }

        @Test
        void approvingTwiceTakesEffectOnce() {                                   // T-Approval-8a, one after the other
            service.approve(ben, loan);

            assertThat(service.approve(ben, loan)).isEqualTo(new Outcome.Rejected("ledger.approval.notPending"));
            assertThat(net(ben)).isEqualTo(-2000);
            assertThat(history(loan)).hasSize(1);
            assertThat(alerts(loan)).filteredOn(a -> a.get(1).equals("settlement_alert")).hasSize(1);
        }

        @Test
        void aFailurePartwayLeavesTheItemPendingWithNothingChanged() {          // T-Approval-5a, CON-7
            doThrow(new IllegalStateException("outbox unavailable"))
                    .when(outbox).enqueue(any(), any(), any());

            assertThatThrownBy(() -> service.approve(ben, loan)).hasMessage("outbox unavailable");

            assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.PENDING);
            assertThat(history(loan)).as("the history row written before the failure is rolled back").isEmpty();
            assertThat(net(ama)).isZero();
            assertThat(net(ben)).isZero();
            assertThat(service.queue(ben).toDecide()).extracting(LedgerTransaction::id).containsExactly(loan);
        }
    }

    // ---- approving a repayment ----------------------------------------------------

    /**
     * Ama lent Ben $9.00 and approved it; Ben recorded paying it back, so Ama decides.
     * On a repayment the payer is the creditor (SRS 4.2), so approving it brings both
     * positions back to zero.
     */
    @Nested
    class ApproveRepayment {

        UUID lent;
        UUID repaid;

        @BeforeEach
        void benOwesAmaAndSaysHePaid() {
            lent = ledger.approvedLoan(ama, ben, 900, USD);
            repaid = ledger.repayment(lent);
        }

        @Test
        void theLenderApprovesAndTheLoanIsSettled() {                           // T-Repay-4a, Ledger.Repayment-3
            assertThat(service.approve(ama, repaid)).isInstanceOf(Outcome.Recorded.class);

            assertThat(ledger.status(repaid)).isEqualTo(TransactionStatus.APPROVED);
            assertThat(ledger.repository().loanLifecycle(lent)).isEqualTo(LoanLifecycle.SETTLED);
            assertThat(net(ama)).isZero();
            assertThat(net(ben)).isZero();
            assertThat(alerts(repaid)).contains(List.of(ben, "settlement_alert"));
        }

        @Test
        void theBorrowerCannotApproveTheirOwnRepayment() {                      // T-Repay-3a
            assertThatThrownBy(() -> service.approve(ben, repaid)).isInstanceOf(NotPermittedException.class);
            assertThat(ledger.status(repaid)).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void aSecondRepaymentOfASettledLoanIsRefusedAndStaysPending() {         // Ledger.Repayment-10
            UUID again = ledger.repayment(lent);
            service.approve(ama, repaid);

            assertThat(service.approve(ama, again))
                    .isEqualTo(new Outcome.Rejected("ledger.repayment.loan.notOutstanding"));
            assertThat(ledger.status(again)).isEqualTo(TransactionStatus.PENDING);
            assertThat(net(ben)).isZero();
            assertThat(history(again)).isEmpty();
        }

        @Test
        void aRepaymentOfAReversedLoanIsRefusedAndStaysPending() {              // Ledger.Repayment-10, T-Repay-10b
            ledger.approve(ledger.correction(lent));

            assertThat(service.approve(ama, repaid))
                    .isEqualTo(new Outcome.Rejected("ledger.repayment.loan.notOutstanding"));
            assertThat(ledger.status(repaid)).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void theRefusedRepaymentCanStillBeDeclinedOrCancelled() {               // Ledger.Repayment-10
            UUID again = ledger.repayment(lent);
            service.approve(ama, repaid);
            service.approve(ama, again);

            assertThat(service.cancel(ben, again)).isInstanceOf(Outcome.Recorded.class);
        }
    }

    // ---- approving a correction ---------------------------------------------------

    /**
     * Ama lent Ben $9.00 and it was approved. A compensating entry swaps the parties of
     * the row it reverses (SRS 4.2), and the fixture records it as raised by that row's
     * debtor, so on a correction of the loan Ben is the initiator and Ama decides.
     */
    @Nested
    class ApproveCorrection {

        UUID lent;

        @BeforeEach
        void amaLentBen() {
            lent = ledger.approvedLoan(ama, ben, 900, USD);
        }

        @Test
        void correctingAnOutstandingLoanReversesIt() {                         // T-Hist-10b, L3
            UUID correction = ledger.correction(lent);

            assertThat(service.approve(ama, correction)).isInstanceOf(Outcome.Recorded.class);

            assertThat(ledger.repository().loanLifecycle(lent)).isEqualTo(LoanLifecycle.REVERSED);
            assertThat(net(ama)).isZero();
            assertThat(net(ben)).isZero();
            assertThat(alerts(correction)).contains(List.of(ben, "settlement_alert"));
        }

        @Test
        void aCorrectionOfACorrectionIsRefusedAndStaysPending() {              // T-Hist-9c, CR-17 second guard
            UUID first = ledger.approve(ledger.correction(lent));
            UUID second = ledger.correction(first);                            // written directly; Ama raised it

            assertThat(service.approve(ben, second))
                    .isEqualTo(new Outcome.Rejected("ledger.history.undo.isCorrection"));
            assertThat(ledger.status(second)).isEqualTo(TransactionStatus.PENDING);
            assertThat(ledger.repository().loanLifecycle(lent)).as("still undone").isEqualTo(LoanLifecycle.REVERSED);
        }

        @Test
        void aRepaidLoanCannotBeCorrectedUntilItsRepaymentIs() {               // T-Hist-11a, T-Hist-11b
            UUID repaid = ledger.approve(ledger.repayment(lent));
            UUID correction = ledger.correction(lent);

            assertThat(service.approve(ama, correction))
                    .isEqualTo(new Outcome.Rejected("ledger.history.correction.conflictsWithRepayment"));
            assertThat(ledger.status(correction)).isEqualTo(TransactionStatus.PENDING);
            assertThat(history(correction)).isEmpty();

            UUID undoRepayment = ledger.correction(repaid);                    // Ama raises it; Ben decides
            assertThat(service.approve(ben, undoRepayment)).isInstanceOf(Outcome.Recorded.class);
            assertThat(ledger.repository().loanLifecycle(lent)).isEqualTo(LoanLifecycle.OUTSTANDING);

            assertThat(service.approve(ama, correction)).isInstanceOf(Outcome.Recorded.class);
            assertThat(ledger.repository().loanLifecycle(lent)).isEqualTo(LoanLifecycle.REVERSED);
            assertThat(net(ama)).isZero();
        }

        @Test
        void correctingARepaymentPutsTheDebtBack() {                           // L4, T-Repay-10a
            UUID repaid = ledger.approve(ledger.repayment(lent));
            UUID correction = ledger.correction(repaid);                       // Ama raises it; Ben decides

            assertThat(service.approve(ben, correction)).isInstanceOf(Outcome.Recorded.class);

            assertThat(ledger.repository().loanLifecycle(lent)).isEqualTo(LoanLifecycle.OUTSTANDING);
            assertThat(net(ben)).isEqualTo(-900);
        }

        @Test
        void aRecordAlreadyCorrectedRefusesASecondCorrection() {               // INT-6
            UUID first = ledger.correction(lent);
            UUID second = ledger.correction(lent);
            service.approve(ama, first);

            assertThat(service.approve(ama, second))
                    .isEqualTo(new Outcome.Rejected("ledger.history.correction.alreadyCorrected"));
            assertThat(ledger.status(second)).isEqualTo(TransactionStatus.PENDING);
            assertThat(net(ama)).isZero();
        }

        @Test
        void whoeverRaisedTheCorrectionCannotApproveIt() {                     // Ledger.Approval-3
            UUID correction = ledger.correction(lent);

            assertThatThrownBy(() -> service.approve(ben, correction)).isInstanceOf(NotPermittedException.class);
            assertThat(ledger.status(correction)).isEqualTo(TransactionStatus.PENDING);
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

    /**
     * Ben taps Approve twice at the same moment, 50 times over (a double tap, or two
     * devices). Exactly one approval takes effect each time, and the balance moves once
     * (Ledger.Approval-8).
     */
    @Test
    void twoApprovalsAtTheSameMomentTakeEffectOnce() throws Exception {         // T-Approval-8a
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                UUID item = ledger.loan(ama, ben, 100, USD);
                CountDownLatch start = new CountDownLatch(1);
                Callable<Outcome> tap = () -> { start.await(); return service.approve(ben, item); };
                Future<Outcome> a = pool.submit(tap);
                Future<Outcome> b = pool.submit(tap);
                start.countDown();

                List<Outcome> outcomes = List.of(a.get(), b.get());
                assertThat(outcomes).as("round %d", round)
                        .filteredOn(o -> o instanceof Outcome.Recorded).hasSize(1);
                assertThat(history(item)).as("round %d", round).hasSize(1);
                assertThat(net(ben)).as("round %d", round).isEqualTo(-100L * (round + 1));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * T-Repay-8b through the service. Ben recorded repaying the same $1.00 loan twice
     * (two devices); Ama approves both at the same moment, 50 times over. The loan lock
     * makes exactly one count; the other is refused as no longer outstanding and stays
     * pending (INT-5, Ledger.Repayment-10).
     */
    @Test
    void twoRepaymentsOfOneLoanApprovedAtOnceSettleItOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                UUID lent = ledger.approvedLoan(ama, ben, 100, USD);
                UUID first = ledger.repayment(lent);
                UUID second = ledger.repayment(lent);
                CountDownLatch start = new CountDownLatch(1);
                Future<Outcome> a = pool.submit(() -> { start.await(); return service.approve(ama, first); });
                Future<Outcome> b = pool.submit(() -> { start.await(); return service.approve(ama, second); });
                start.countDown();

                List<Outcome> outcomes = List.of(a.get(), b.get());
                assertThat(outcomes).as("round %d", round)
                        .filteredOn(o -> o instanceof Outcome.Recorded).hasSize(1);
                assertThat(outcomes).as("round %d", round)
                        .contains(new Outcome.Rejected("ledger.repayment.loan.notOutstanding"));
                assertThat(net(ben)).as("round %d: every loan repaid exactly once", round).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * T-Hist-11c through the service. Two corrections of the same loan, both pending,
     * approved at the same moment, 50 times over. Locking the loan makes exactly one
     * count; the other is refused as already corrected (INT-6).
     */
    @Test
    void twoCorrectionsOfOneLoanApprovedAtOnceReverseItOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                UUID lent = ledger.approvedLoan(ama, ben, 100, USD);
                UUID first = ledger.correction(lent);
                UUID second = ledger.correction(lent);
                CountDownLatch start = new CountDownLatch(1);
                Future<Outcome> a = pool.submit(() -> { start.await(); return service.approve(ama, first); });
                Future<Outcome> b = pool.submit(() -> { start.await(); return service.approve(ama, second); });
                start.countDown();

                List<Outcome> outcomes = List.of(a.get(), b.get());
                assertThat(outcomes).as("round %d", round)
                        .filteredOn(o -> o instanceof Outcome.Recorded).hasSize(1);
                assertThat(outcomes).as("round %d", round)
                        .contains(new Outcome.Rejected("ledger.history.correction.alreadyCorrected"));
                assertThat(net(ben)).as("round %d: every loan reversed exactly once", round).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * T-Hist-11g. A repayment of a loan and a correction of the same loan, both pending,
     * approved at the same moment, 50 times over. Both lock the loan, so exactly one is
     * approved and the other is refused with its own message; the loan is never both
     * Settled and Reversed (INT-5).
     */
    @Test
    void aRepaymentAndACorrectionOfOneLoanNeverBothCount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                UUID lent = ledger.approvedLoan(ama, ben, 100, USD);
                UUID repayment = ledger.repayment(lent);
                UUID correction = ledger.correction(lent);
                CountDownLatch start = new CountDownLatch(1);
                Future<Outcome> a = pool.submit(() -> { start.await(); return service.approve(ama, repayment); });
                Future<Outcome> b = pool.submit(() -> { start.await(); return service.approve(ama, correction); });
                start.countDown();

                Outcome repaid = a.get();
                Outcome corrected = b.get();
                assertThat(List.of(repaid, corrected)).as("round %d", round)
                        .filteredOn(o -> o instanceof Outcome.Recorded).hasSize(1);
                if (repaid instanceof Outcome.Recorded) {
                    assertThat(corrected).as("round %d", round)
                            .isEqualTo(new Outcome.Rejected("ledger.history.correction.conflictsWithRepayment"));
                    assertThat(ledger.repository().loanLifecycle(lent)).isEqualTo(LoanLifecycle.SETTLED);
                } else {
                    assertThat(repaid).as("round %d", round)
                            .isEqualTo(new Outcome.Rejected("ledger.repayment.loan.notOutstanding"));
                    assertThat(ledger.repository().loanLifecycle(lent)).isEqualTo(LoanLifecycle.REVERSED);
                }
                assertThat(net(ben)).as("round %d", round).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
