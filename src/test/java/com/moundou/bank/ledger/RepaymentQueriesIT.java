package com.moundou.bank.ledger;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.XAF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The query behind the repayment form (MB-13): which loans a borrower is
 * offered to repay (Ledger.Repayment-8). Ama lent to Ben; Ben is the borrower.
 */
class RepaymentQueriesIT {

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

    @Test
    void anApprovedLoanIsOfferedToItsBorrowerOnly() {                          // T-Repay-1a
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID other = ledger.approvedLoan(cleo, ben, 50_000, XAF);              // from another lender, another currency

        assertThat(repository.outstandingLoansOwedBy(ben))
                .extracting(LedgerTransaction::id).containsExactly(lent, other);
        assertThat(repository.outstandingLoansOwedBy(ama)).as("the lender owes nothing").isEmpty();
        assertThat(repository.outstandingLoansOwedBy(cleo)).isEmpty();
    }

    @Test
    void pendingDeclinedAndCancelledLoansAreNotOffered() {                     // T-Repay-8a
        ledger.loan(ama, ben, 100, USD);
        ledger.decline(ledger.loan(ama, ben, 200, USD));
        ledger.cancel(ledger.loan(ama, ben, 300, USD));

        assertThat(repository.outstandingLoansOwedBy(ben)).isEmpty();
    }

    @Test
    void aSettledLoanIsNotOffered() {                                          // T-Repay-8a
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        ledger.approve(ledger.repayment(lent));

        assertThat(repository.outstandingLoansOwedBy(ben)).isEmpty();
    }

    @Test
    void aReversedLoanIsNotOffered() {                                         // T-Repay-8d
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        ledger.approve(ledger.correction(lent));

        assertThat(repository.outstandingLoansOwedBy(ben)).isEmpty();
    }

    @Test
    void aLoanWithAPendingRepaymentIsHiddenUntilThatRepaymentIsDecided() {     // T-Repay-8e
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID repayment = ledger.repayment(lent);
        assertThat(repository.outstandingLoansOwedBy(ben)).isEmpty();

        ledger.decline(repayment);
        assertThat(repository.outstandingLoansOwedBy(ben)).extracting(LedgerTransaction::id).containsExactly(lent);
    }

    @Test
    void aLoanWhoseRepaymentWasReversedIsOfferedAgain() {                      // T-Repay-10a
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID repaid = ledger.approve(ledger.repayment(lent));
        ledger.approve(ledger.correction(repaid));

        assertThat(repository.outstandingLoansOwedBy(ben)).extracting(LedgerTransaction::id).containsExactly(lent);
        assertThat(repository.loanLifecycle(lent)).isEqualTo(LoanLifecycle.OUTSTANDING);
    }

    @Test
    void aPendingOrDeclinedCorrectionDoesNotHideTheLoan() {
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        ledger.correction(lent);                                               // pending
        ledger.decline(ledger.correction(lent));

        assertThat(repository.outstandingLoansOwedBy(ben)).extracting(LedgerTransaction::id).containsExactly(lent);
    }

    @Test
    void everyLoanOfferedIsOutstandingByTheLifecycleToo() {
        UUID a = ledger.approvedLoan(ama, ben, 100, USD);
        UUID b = ledger.approvedLoan(ama, ben, 200, USD);
        ledger.approve(ledger.repayment(b));
        UUID c = ledger.approvedLoan(cleo, ben, 300, USD);
        ledger.approve(ledger.correction(c));

        assertThat(repository.outstandingLoansOwedBy(ben)).allSatisfy(loan ->
                assertThat(repository.loanLifecycle(loan.id())).isEqualTo(LoanLifecycle.OUTSTANDING));
        assertThat(repository.outstandingLoansOwedBy(ben)).extracting(LedgerTransaction::id).containsExactly(a);
    }
}
