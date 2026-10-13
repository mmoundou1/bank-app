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
 * behind approving a correction. These tests are about what the
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
    }
}
