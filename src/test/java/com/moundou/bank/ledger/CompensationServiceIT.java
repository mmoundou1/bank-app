package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.ledger.CompensationService.Outcome;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slow suite. Raising a compensating entry (MB-14 journey 2) through the real Spring
 * context, against Postgres. Approving it is DualConfirmationService's job and is tested
 * there; here the correction has to be saved Pending, or refused with nothing saved.
 *
 * Ama lent Ben $100.00, approved. Either of them may undo it.
 */
@SpringBootTest
class CompensationServiceIT {

    /** 2026-10-28 23:30 UTC: already the 29th in Douala, where the fixture members live. */
    static final Instant NOW = Instant.parse("2026-10-28T23:30:00Z");

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

    @Autowired CompensationService compensations;
    @Autowired MutableClock clock;

    JdbcClient jdbc;
    LedgerFixtures ledger;
    UUID ama;
    UUID ben;
    UUID cleo;
    UUID loan;

    @BeforeEach
    void amaLentBenOneHundredDollars() {
        clock.set(NOW);
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        ama = ledger.member("Ama");
        ben = ledger.member("Ben");
        cleo = ledger.member("Cleo");
        loan = ledger.approvedLoan(ama, ben, 10_000, USD);
    }

    @Nested
    class Recorded {

        @Test
        void isAPendingFullReversalOfTheTarget() {                          // T-Hist-5a, -8a, -10a
            LedgerTransaction c = recorded(undo(ben, loan, "Never happened"));

            assertThat(c.kind()).isEqualTo(TransactionKind.COMPENSATING);
            assertThat(c.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(c.correctsTransactionId()).isEqualTo(loan);
            assertThat(c.settlesTransactionId()).isNull();
            assertThat(c.creditor()).as("parties swapped").isEqualTo(ben);
            assertThat(c.debtor()).isEqualTo(ama);
            assertThat(c.amountMinor()).isEqualTo(10_000);
            assertThat(c.currency()).isEqualTo(USD);
            assertThat(c.initiatedBy()).isEqualTo(ben);
            assertThat(c.note()).isEqualTo("Never happened");
            assertThat(c.openingBalance()).isFalse();
        }

        @Test
        void isDatedTodayInTheInitiatorsZone() {                             // CON-8
            assertThat(recorded(undo(ben, loan, null)).transactionDate())
                    .as("23:30 UTC on the 28th is the 29th in Douala").isEqualTo(LocalDate.of(2026, 10, 29));
        }

        @Test
        void leavesTheTargetUnchanged() {                                    // T-Hist-5a, Data-6
            LedgerTransaction before = ledger.repository().findById(loan).orElseThrow();
            recorded(undo(ben, loan, null));
            assertThat(ledger.repository().findById(loan).orElseThrow()).isEqualTo(before);
        }

        @Test
        void startsItsOwnHistoryAndAlertsTheOtherParty() {                   // T-Hist-8a, T-NPend-1b
            LedgerTransaction c = recorded(undo(ben, loan, null));

            assertThat(jdbc.sql("SELECT from_status, to_status, actor_id FROM transaction_status_history"
                            + " WHERE transaction_id = :t")
                    .param("t", c.id())
                    .query((rs, n) -> java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)))
                    .list())
                    .containsExactly(java.util.Arrays.asList(null, "pending", ben));
            assertThat(jdbc.sql("SELECT count(*) FROM transaction_status_history WHERE transaction_id = :t")
                    .param("t", loan).query(Integer.class).single())
                    .as("nothing added to the target's history").isZero();
            assertThat(jdbc.sql("SELECT recipient_id, kind FROM notification WHERE transaction_id = :t")
                    .param("t", c.id())
                    .query((rs, n) -> List.<Object>of(rs.getObject(1, UUID.class), rs.getString(2))).list())
                    .containsExactly(List.of(ama, "pending_alert"));
        }

        @Test
        void eitherPartyMayRaiseIt() {                                       // T-Hist-9a
            LedgerTransaction c = recorded(undo(ama, loan, null));            // the lender this time
            assertThat(c.initiatedBy()).isEqualTo(ama);
            assertThat(alertRecipient(c.id())).isEqualTo(ben);
        }

        @Test
        void aRepaymentCanBeUndone() {                                       // T-Hist-11b, first half
            UUID repaid = ledger.approve(ledger.repayment(loan));
            LedgerTransaction c = recorded(undo(ama, repaid, null));
            assertThat(c.correctsTransactionId()).isEqualTo(repaid);
            assertThat(c.creditor()).as("the repayment's parties, swapped").isEqualTo(ama);
        }

        @Test
        void aBlankNoteIsNoNote() {
            assertThat(recorded(undo(ben, loan, "   ")).note()).isNull();
        }
    }

    @Nested
    class Retried {

        @Test
        void theSameSubmissionTwiceIsRecordedOnce() {                         // Ledger.Entry-14 applies too
            UUID key = UUID.randomUUID();
            LedgerTransaction first = recorded(compensations.compensation(ben, loan, null, key));
            Outcome again = compensations.compensation(ben, loan, null, key);

            assertThat(again).isEqualTo(new Outcome.Recorded(first, true));
            assertThat(correctionsOf(loan)).isOne();
        }

        @Test
        void aDoubleTapAtTheSameMomentIsRecordedOnce() throws Exception {
            for (int run = 0; run < 20; run++) {
                amaLentBenOneHundredDollars();
                UUID key = UUID.randomUUID();
                List<Outcome> outcomes = together(2, () -> compensations.compensation(ben, loan, null, key));

                assertThat(outcomes).as("run %d", run).allMatch(o -> o instanceof Outcome.Recorded);
                assertThat(outcomes).filteredOn(o -> ((Outcome.Recorded) o).alreadyRecorded()).hasSize(1);
                assertThat(correctionsOf(loan)).isOne();
            }
        }

        @Test
        void aNewSubmissionAfterADeclinedOneIsAllowed() {
            UUID first = recorded(undo(ben, loan, null)).id();
            ledger.decline(first);
            assertThat(recorded(undo(ben, loan, null)).id()).isNotEqualTo(first);
        }
    }

    @Nested
    class Refused {

        @Test
        void aCorrectionCannotBeUndone() {                                   // T-Hist-9c
            UUID c = ledger.approve(ledger.correction(loan));
            assertThat(undo(ama, c, null)).isEqualTo(new Outcome.Rejected("ledger.history.undo.isCorrection"));
            assertThat(correctionsOf(c)).isZero();
        }

        @Test
        void aRecordThatNeverCountedCannotBeUndone() {
            UUID pending = ledger.loan(ama, ben, 500, USD);
            assertThat(undo(ben, pending, null)).isEqualTo(new Outcome.Rejected("ledger.history.undo.notApproved"));
            assertThat(correctionsOf(pending)).isZero();
        }

        @Test
        void aRecordAlreadyUndoneCannotBeUndoneAgain() {                     // INT-6
            ledger.approve(ledger.correction(loan));
            assertThat(undo(ben, loan, null))
                    .isEqualTo(new Outcome.Rejected("ledger.history.correction.alreadyCorrected"));
        }

        @Test
        void aRepaidLoanWaitsForItsRepaymentToBeUndone() {                   // T-Hist-11a, -11b
            UUID repaid = ledger.approve(ledger.repayment(loan));
            assertThat(undo(ben, loan, null))
                    .isEqualTo(new Outcome.Rejected("ledger.history.correction.conflictsWithRepayment"));

            ledger.approve(recorded(undo(ama, repaid, null)).id());            // undo the repayment first
            assertThat(undo(ben, loan, null)).isInstanceOf(Outcome.Recorded.class);
        }
    }

    @Nested
    class NotPermitted {

        @Test
        void someoneOutsideTheRecord() {                                     // T-Hist-9b, Ledger.History-7
            assertThatThrownBy(() -> undo(cleo, loan, null)).isInstanceOf(NotPermittedException.class);
            assertThat(correctionsOf(loan)).isZero();
        }

        @Test
        void someoneOutsideACorrectionLearnsNothingAboutIt() {               // authorization first
            UUID c = ledger.approve(ledger.correction(loan));
            assertThatThrownBy(() -> undo(cleo, c, null)).isInstanceOf(NotPermittedException.class);
        }

        @Test
        void anUnknownRecordGetsTheSameAnswer() {
            assertThatThrownBy(() -> undo(ben, UUID.randomUUID(), null)).isInstanceOf(NotPermittedException.class);
        }

        @Test
        void aDeactivatedMember() {                                          // Auth.Login-5
            jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :id").param("id", ben).update();
            assertThatThrownBy(() -> undo(ben, loan, null)).isInstanceOf(NotPermittedException.class);
        }
    }

    @Test
    void theConfirmScreenGetsTheSameAnswerAsTheSubmit() {
        assertThat(compensations.targetDetails(ben, loan).check()).isEqualTo(new UndoRules.Check.Undoable());
        ledger.approve(ledger.repayment(loan));
        assertThat(compensations.targetDetails(ben, loan).check())
                .isEqualTo(new UndoRules.Check.Refused("ledger.history.correction.conflictsWithRepayment"));
        assertThatThrownBy(() -> compensations.targetDetails(cleo, loan)).isInstanceOf(NotPermittedException.class);
    }

    // ---- helpers ------------------------------------------------------------------

    Outcome undo(UUID actor, UUID target, String note) {
        return compensations.compensation(actor, target, note, UUID.randomUUID());
    }

    LedgerTransaction recorded(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Recorded.class);
        return ((Outcome.Recorded) outcome).transaction();
    }

    int correctionsOf(UUID target) {
        return jdbc.sql("SELECT count(*) FROM transaction WHERE corrects_transaction_id = :t")
                .param("t", target).query(Integer.class).single();
    }

    UUID alertRecipient(UUID transactionId) {
        return jdbc.sql("SELECT recipient_id FROM notification WHERE transaction_id = :t")
                .param("t", transactionId).query(UUID.class).single();
    }

    /** Runs {@code attempt} on {@code n} threads at once and collects every answer. */
    static List<Outcome> together(int n, Callable<Outcome> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Outcome>> futures = pool.invokeAll(IntStream.range(0, n).mapToObj(i -> attempt).toList());
            List<Outcome> outcomes = new java.util.ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
