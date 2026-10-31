package com.moundou.bank.ledger;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The query that loads the rows UndoRules needs when a correction is
 * submitted (MB-14): a target's corrections, its repayments and their corrections.
 */
class UndoQueriesIT {

    private static JdbcClient jdbc;
    private LedgerFixtures ledger;
    private TransactionRepository repository;
    private UUID ama;
    private UUID ben;

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
    }

    @Test
    void aLoanWithNothingAgainstItHasNoRelatedRows() {
        assertThat(repository.relatedTo(ledger.approvedLoan(ama, ben, 2000, USD))).isEmpty();
    }

    @Test
    void aLoansRepaymentsAndTheirCorrectionsAreRelatedInAnyStatus() {
        UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID repaid = ledger.approve(ledger.repayment(loan));
        UUID undoRepayment = ledger.approve(ledger.correction(repaid));
        UUID declinedRepayment = ledger.decline(ledger.repayment(loan));
        UUID pendingUndoOfLoan = ledger.correction(loan);

        assertThat(repository.relatedTo(loan)).extracting(LedgerTransaction::id)
                .containsExactlyInAnyOrder(repaid, undoRepayment, declinedRepayment, pendingUndoOfLoan);
    }

    @Test
    void aRepaymentsRelatedRowsAreItsCorrectionsOnly() {
        UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID repaid = ledger.approve(ledger.repayment(loan));
        UUID undo = ledger.correction(repaid);

        assertThat(repository.relatedTo(repaid)).extracting(LedgerTransaction::id).containsExactly(undo);
    }

    @Test
    void otherRecordsRowsAreNotRelated() {
        UUID loan = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID other = ledger.approvedLoan(ama, ben, 500, USD);
        ledger.approve(ledger.repayment(other));
        ledger.correction(other);

        assertThat(repository.relatedTo(loan)).isEmpty();
    }
}
