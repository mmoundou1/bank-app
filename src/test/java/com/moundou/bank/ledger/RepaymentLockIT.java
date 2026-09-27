package com.moundou.bank.ledger;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.XAF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. INT-5 as changed in SRS 1.10 (CR-14): a loan is settled by at most one
 * approved repayment that has not been reversed, enforced by locking the loan row
 * rather than by a constraint.
 *
 * {@link #approveRepayment} is the reference sequence from
 * {@link TransactionRepository}'s class comment: lock the loan, lock the repayment,
 * check both, decide. MB-12's approval service must take the same steps in the same
 * order; the history and outbox rows it also writes do not change the locking.
 */
class RepaymentLockIT {

    enum Outcome { APPROVED, NOT_OUTSTANDING, NOT_PENDING }

    private static JdbcClient jdbc;
    private static TransactionTemplate tx;
    private LedgerFixtures ledger;
    private TransactionRepository repository;
    private UUID lender;
    private UUID borrower;

    @BeforeAll
    static void migrate() {
        TestDatabase.migrateFromEmpty();
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        tx = new TransactionTemplate(new DataSourceTransactionManager(TestDatabase.dataSource()));
    }

    @BeforeEach
    void freshLedger() {
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        repository = ledger.repository();
        lender = ledger.member("Lender");
        borrower = ledger.member("Borrower");
    }

    Outcome approveRepayment(UUID repaymentId) {
        return approveRepayment(repaymentId, () -> { });
    }

    /** @param whileHoldingLoanLock runs after step 1, while this transaction holds the loan lock */
    Outcome approveRepayment(UUID repaymentId, Runnable whileHoldingLoanLock) {
        UUID loanId = repository.findById(repaymentId).orElseThrow().settlesTransactionId();
        return tx.execute(status -> {
            repository.lockForUpdate(loanId).orElseThrow();                              // 1. referenced row first
            whileHoldingLoanLock.run();
            var repayment = repository.lockForUpdate(repaymentId).orElseThrow();        // 2. then the item
            if (repayment.status() != TransactionStatus.PENDING) {                     // 3.
                return Outcome.NOT_PENDING;
            }
            if (repository.loanLifecycle(loanId) != LoanLifecycle.OUTSTANDING) {        // 4. Ledger.Repayment-10
                return Outcome.NOT_OUTSTANDING;
            }
            repository.recordDecision(repaymentId, TransactionStatus.APPROVED, Instant.now(), null);  // 5.
            return Outcome.APPROVED;
        });
    }

    private List<UUID> approvedRepaymentsOf(UUID loan) {
        return jdbc.sql("SELECT transaction_id FROM transaction"
                        + " WHERE settles_transaction_id = :loan AND status = 'approved'")
                .param("loan", loan).query(UUID.class).list();
    }

    private long borrowerNet(String currency) {
        return jdbc.sql("SELECT COALESCE(SUM(net_minor), 0) FROM member_net"
                        + " WHERE member_id = :m AND currency = :c")
                .param("m", borrower).param("c", currency).query(Long.class).single();
    }

    /**
     * T-Repay-8b. Two repayments of one loan are approved at the same moment; exactly
     * one wins.
     *
     * The overlap is forced rather than left to chance: approval A takes the loan lock
     * and then waits until Postgres reports approval B blocked on a lock. Only then does
     * A finish. So B provably waited on A's lock, and the assertion is about what B did
     * after the wait, which is the whole point of the lock.
     */
    @Test
    void concurrentApprovalsOfTwoRepaymentsLeaveExactlyOneApproved() throws Exception {
        UUID loan = ledger.approvedLoan(lender, borrower, 12_500, USD);
        UUID first = ledger.repayment(loan);
        UUID second = ledger.repayment(loan);

        CountDownLatch aHoldsLock = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Outcome> a = CompletableFuture.supplyAsync(() -> approveRepayment(first, () -> {
                aHoldsLock.countDown();
                awaitAnotherSessionBlockedOnALock();
            }), pool);

            assertThat(aHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Outcome> b = CompletableFuture.supplyAsync(() -> approveRepayment(second), pool);

            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(Outcome.APPROVED);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(Outcome.NOT_OUTSTANDING);
        } finally {
            pool.shutdownNow();
        }

        assertThat(approvedRepaymentsOf(loan)).containsExactly(first);
        // Ledger.Repayment-10: the refused approval leaves the repayment pending, for the
        // lender to decline or the borrower to cancel.
        assertThat(ledger.status(second)).isEqualTo(TransactionStatus.PENDING);
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.SETTLED);
        assertThat(borrowerNet("USD")).isZero();
    }

    /**
     * The same race without any choreography: both approvals released together by a
     * barrier, many times. Whichever wins, there is never more than one.
     */
    @Test
    void unchoreographedRacesNeverApproveTwo() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                UUID loan = ledger.approvedLoan(lender, borrower, 1_000 + round, XAF);
                UUID first = ledger.repayment(loan);
                UUID second = ledger.repayment(loan);
                CyclicBarrier start = new CyclicBarrier(2);

                var a = CompletableFuture.supplyAsync(() -> { await(start); return approveRepayment(first); }, pool);
                var b = CompletableFuture.supplyAsync(() -> { await(start); return approveRepayment(second); }, pool);

                assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                        .as("round %d", round)
                        .containsExactlyInAnyOrder(Outcome.APPROVED, Outcome.NOT_OUTSTANDING);
                assertThat(approvedRepaymentsOf(loan)).as("round %d", round).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * T-Repay-10a (CR-14 Q1). Once the repayment is reversed by an approved compensating
     * entry, the debt is back, and the loan can be repaid again. Under the old unique
     * constraint on settles_transaction_id this was impossible.
     */
    @Test
    void afterARepaymentIsReversedTheLoanCanBeRepaidAgain() {
        UUID loan = ledger.approvedLoan(lender, borrower, 30_000, USD);
        UUID firstRepayment = ledger.repayment(loan);
        assertThat(approveRepayment(firstRepayment)).isEqualTo(Outcome.APPROVED);
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.SETTLED);

        ledger.approve(ledger.correction(firstRepayment));   // L4: the repayment is reversed
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.OUTSTANDING);
        assertThat(borrowerNet("USD")).as("the debt is back").isEqualTo(-30_000L);

        UUID secondRepayment = ledger.repayment(loan);
        assertThat(approveRepayment(secondRepayment)).isEqualTo(Outcome.APPROVED);
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.SETTLED);
        assertThat(borrowerNet("USD")).isZero();
        assertThat(approvedRepaymentsOf(loan)).containsExactlyInAnyOrder(firstRepayment, secondRepayment);
    }

    /** Ledger.Repayment-10 at approval: a settled loan refuses a second repayment. */
    @Test
    void aSettledLoanRefusesAnotherRepayment() {
        UUID loan = ledger.approvedLoan(lender, borrower, 500, USD);
        UUID extra = ledger.repayment(loan);
        assertThat(approveRepayment(ledger.repayment(loan))).isEqualTo(Outcome.APPROVED);
        assertThat(approveRepayment(extra)).isEqualTo(Outcome.NOT_OUTSTANDING);
    }

    /** CR-14 Q2: a reversed loan is not Outstanding, so it cannot be repaid (it would count the reversal twice). */
    @Test
    void aReversedLoanRefusesARepayment() {
        UUID loan = ledger.approvedLoan(lender, borrower, 500, USD);
        UUID repayment = ledger.repayment(loan);
        ledger.approve(ledger.correction(loan));   // L3
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.REVERSED);
        assertThat(approveRepayment(repayment)).isEqualTo(Outcome.NOT_OUTSTANDING);
    }

    /** Ledger.Approval-8, -9: approving twice has no second effect. */
    @Test
    void approvingTheSameRepaymentTwiceIsRefusedTheSecondTime() {
        UUID loan = ledger.approvedLoan(lender, borrower, 500, USD);
        UUID repayment = ledger.repayment(loan);
        assertThat(approveRepayment(repayment)).isEqualTo(Outcome.APPROVED);
        assertThat(approveRepayment(repayment)).isEqualTo(Outcome.NOT_PENDING);
    }

    /** Technical Design 5, transitions L1 to L5, including chains of corrections. */
    @Test
    void loanLifecycleFollowsTheCorrectionChain() {
        UUID pending = ledger.loan(lender, borrower, 900, USD);
        assertThat(repository.loanLifecycle(pending)).isEqualTo(LoanLifecycle.NOT_AN_APPROVED_LOAN);

        UUID loan = ledger.approve(pending);                                          // L1
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.OUTSTANDING);

        UUID c1 = ledger.approve(ledger.correction(loan));                            // L3
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.REVERSED);

        UUID c2 = ledger.approve(ledger.correction(c1));                              // L5
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.OUTSTANDING);

        ledger.approve(ledger.correction(c2));                                        // reversed again
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.REVERSED);

        assertThat(repository.loanLifecycle(c1))
                .as("a compensating entry is not a loan").isEqualTo(LoanLifecycle.NOT_AN_APPROVED_LOAN);
    }

    @Test
    void onlyAnApprovedCorrectionCountsAsAReversal() {
        UUID loan = ledger.approvedLoan(lender, borrower, 900, USD);
        ledger.decline(ledger.correction(loan));
        ledger.correction(loan);   // still pending
        assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.OUTSTANDING);
    }

    // ---- helpers ------------------------------------------------------------------

    /**
     * Polls Postgres until some other session in this database is waiting on a lock.
     *
     * Uses a raw connection of its own in autocommit mode on purpose. Called from inside
     * approval A, a JdbcClient would join A's transaction (Spring binds the connection to
     * the thread), and Postgres freezes what pg_stat_activity shows for the length of a
     * transaction, so the probe would never see B arrive.
     */
    private static void awaitAnotherSessionBlockedOnALock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = TestDatabase.dataSource().getConnection()) {
            observer.setAutoCommit(true);
            while (System.nanoTime() < deadline) {
                try (var query = observer.prepareStatement("""
                        SELECT count(*) FROM pg_stat_activity
                         WHERE datname = current_database() AND wait_event_type = 'Lock'""");
                     var rs = query.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                sleep(20);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError("the second approval never blocked on the loan lock");
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
