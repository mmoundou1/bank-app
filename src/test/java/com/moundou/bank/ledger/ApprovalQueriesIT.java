package com.moundou.bank.ledger;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The SQL MB-12's approval service reads: the two queues, and the lookups
 * behind approving a correction (SRS 1.12, CR-15). These tests are about what the
 * queries return; the locking and the decisions themselves are tested with the service.
 */
class ApprovalQueriesIT {

    private static JdbcClient jdbc;
    private LedgerFixtures ledger;
    private TransactionRepository repository;
    private UUID ama;
    private UUID ben;
    private UUID cleo;

    @BeforeAll
    static void migrate() {
        TestDatabase.migrateFromEmpty();
        jdbc = JdbcClient.create(TestDatabase.dataSource());
    }

    @BeforeEach
    void freshLedger() {
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        repository = ledger.repository();
        ama = ledger.member("Ama");
        ben = ledger.member("Ben");
        cleo = ledger.member("Cleo");
    }

    @Nested
    class Queues {

        @Test
        void theCounterpartySeesWhatAwaitsTheirDecisionOldestFirst() {        // Ledger.Approval-1, UI-8
            UUID amaLentBen = ledger.loan(ama, ben, 2000, USD);              // Ben decides
            UUID benLentAma = ledger.loan(ben, ama, 500, USD);               // Ben sent it: not his to decide
            ledger.loan(ama, cleo, 700, USD);                                // not Ben's at all
            UUID declined = ledger.decline(ledger.loan(ama, ben, 300, USD)); // already decided
            UUID repaymentToBen = ledger.repayment(ledger.approvedLoan(ben, ama, 900, USD)); // Ama repays Ben

            assertThat(repository.awaitingDecisionBy(ben))
                    .extracting(LedgerTransaction::id)
                    .containsExactly(amaLentBen, repaymentToBen)
                    .doesNotContain(benLentAma, declined);
        }

        @Test
        void theInitiatorSeesWhatTheySentThatIsStillPending() {              // Ledger.Approval-11, UC-12
            UUID first = ledger.loan(ama, ben, 2000, USD);
            UUID second = ledger.loan(ama, cleo, 100, USD);
            ledger.cancel(ledger.loan(ama, ben, 300, USD));
            ledger.loan(ben, ama, 400, USD);                                 // Ben sent it

            assertThat(repository.awaitingOthersFor(ama))
                    .extracting(LedgerTransaction::id)
                    .containsExactly(first, second);
        }

        @Test
        void aMemberWithNothingPendingGetsEmptyQueues() {
            ledger.approvedLoan(ama, ben, 2000, USD);

            assertThat(repository.awaitingDecisionBy(ben)).isEmpty();
            assertThat(repository.awaitingOthersFor(ama)).isEmpty();
        }
    }

    @Nested
    class Corrections {

        @Test
        void onlyAnApprovedCorrectionCountsAsCorrected() {                   // INT-6
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            ledger.decline(ledger.correction(loan));
            ledger.correction(loan);                                         // pending
            assertThat(repository.hasApprovedCorrection(loan)).isFalse();

            ledger.approve(ledger.correction(loan));
            assertThat(repository.hasApprovedCorrection(loan)).isTrue();
        }

        @Test
        void aCorrectionOfALoanConcernsThatLoan() {
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            UUID correction = ledger.correction(loan);

            assertThat(repository.chainLoanOf(correction)).contains(loan);
        }

        @Test
        void aCorrectionOfARepaymentConcernsTheLoanItSettles() {
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            UUID repayment = ledger.approve(ledger.repayment(loan));
            UUID correction = ledger.correction(repayment);

            assertThat(repository.chainLoanOf(correction)).contains(loan);
        }

        @Test
        void aLongChainIsFollowedBackToItsLoan() {
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            UUID repayment = ledger.approve(ledger.repayment(loan));
            UUID c1 = ledger.approve(ledger.correction(repayment));
            UUID c2 = ledger.approve(ledger.correction(c1));
            UUID c3 = ledger.correction(c2);

            assertThat(repository.chainLoanOf(c3)).contains(loan);
        }

        @Test
        void onlyACompensatingEntryHasAChainLoan() {
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            UUID repayment = ledger.repayment(loan);

            assertThat(repository.chainLoanOf(loan)).isEmpty();
            assertThat(repository.chainLoanOf(repayment)).isEmpty();
            assertThat(repository.chainLoanOf(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    class StandingIfApproved {

        @Test
        void reversingAnUnrepaidLoanLeavesItReversedWithNoRepayment() {     // allowed
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            UUID correction = ledger.correction(loan);

            assertThat(repository.loanStandingIfApproved(loan, correction))
                    .isEqualTo(new LoanStanding(true, 0));
        }

        @Test
        void reversingARepaidLoanWouldLeaveItReversedWhileSettled() {        // Ledger.History-11 first link
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            ledger.approve(ledger.repayment(loan));
            UUID correction = ledger.correction(loan);

            assertThat(repository.loanStandingIfApproved(loan, correction))
                    .isEqualTo(new LoanStanding(true, 1));
        }

        @Test
        void caseA_restoringAReversedRepaymentWouldSettleTheLoanTwice() {   // T-Hist-11e, CR-15
            UUID loan = ledger.approvedLoan(ama, ben, 10000, USD);
            UUID r1 = ledger.approve(ledger.repayment(loan));
            UUID c1 = ledger.approve(ledger.correction(r1));                 // L Outstanding again
            UUID r2 = ledger.approve(ledger.repayment(loan));
            UUID c2 = ledger.correction(c1);                                 // would restore R1

            assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.SETTLED);
            assertThat(repository.loanStandingIfApproved(loan, c2))
                    .isEqualTo(new LoanStanding(false, 2));

            // Reverse R2 first, as the message asks; then restoring R1 is fine.
            ledger.approve(ledger.correction(r2));
            assertThat(repository.loanStandingIfApproved(loan, c2))
                    .isEqualTo(new LoanStanding(false, 1));
        }

        @Test
        void caseB_restoringAReversalWouldLeaveTheLoanReversedAndSettled() { // T-Hist-11f, CR-15
            UUID loan = ledger.approvedLoan(ama, ben, 10000, USD);
            UUID c1 = ledger.approve(ledger.correction(loan));
            UUID c2 = ledger.approve(ledger.correction(c1));                 // L Outstanding again
            ledger.approve(ledger.repayment(loan));
            UUID c3 = ledger.correction(c2);                                 // would restore C1

            assertThat(repository.loanStandingIfApproved(loan, c3))
                    .isEqualTo(new LoanStanding(true, 1));
        }

        @Test
        void askingChangesNothing() {
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            UUID correction = ledger.correction(loan);

            repository.loanStandingIfApproved(loan, correction);

            assertThat(ledger.status(correction)).isEqualTo(TransactionStatus.PENDING);
            assertThat(repository.loanLifecycle(loan)).isEqualTo(LoanLifecycle.OUTSTANDING);
        }

        @Test
        void aPendingCorrectionThatIsNotTheOneAskedAboutDoesNotCount() {
            UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
            UUID repayment = ledger.approve(ledger.repayment(loan));
            ledger.correction(repayment);                                    // pending, someone else's question
            UUID reversal = ledger.correction(loan);

            assertThat(repository.loanStandingIfApproved(loan, reversal))
                    .isEqualTo(new LoanStanding(true, 1));
        }
    }
}
