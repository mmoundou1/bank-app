package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.ledger.RepaymentService.Outcome;
import com.moundou.bank.ledger.RepaymentService.RepaymentRequest;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.XAF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slow suite. Recording a repayment (MB-13) through the real Spring context, against
 * Postgres with the migrations applied. Ama lent Ben $100.00; Ben records paying it back.
 * Approving the repayment is DualConfirmationService's job and is tested there; here the
 * repayment only has to be saved Pending, or refused with nothing saved.
 */
@SpringBootTest
class RepaymentServiceIT {

    /** 2026-10-18 23:30 UTC: already the 19th in Douala, where the fixture members live. */
    static final Instant NOW = Instant.parse("2026-10-18T23:30:00Z");

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

    @Autowired RepaymentService repayments;
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

    // ---- helpers ------------------------------------------------------------------

    RepaymentRequest repay(UUID loanId, String amount, String currency) {
        return new RepaymentRequest(loanId, amount, currency, UUID.randomUUID());
    }

    Outcome submit(UUID actor, RepaymentRequest request) {
        return repayments.submit(actor, request, Locale.ENGLISH);
    }

    LedgerTransaction recorded(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Recorded.class);
        return ((Outcome.Recorded) outcome).transaction();
    }

    Map<String, String> rejected(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
        return ((Outcome.Rejected) outcome).fieldErrors();
    }

    /** Repayments of the loan, whatever their state. */
    int repaymentsOfTheLoan() {
        return jdbc.sql("SELECT count(*) FROM transaction WHERE settles_transaction_id = :l")
                .param("l", loan).query(Integer.class).single();
    }

    Map<String, Long> netPositions() {
        return jdbc.sql("SELECT member_id::text || ' ' || currency, net_minor FROM member_net")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ---- a valid repayment --------------------------------------------------------

    @Nested
    class Recorded {

        @Test
        void isSavedPendingFromBorrowerToLenderAgainstTheLoan() {   // T-Repay-2a, -7b, D2
            LedgerTransaction r = recorded(submit(ben, repay(loan, "100.00", "USD")));

            assertThat(r.kind()).isEqualTo(TransactionKind.REPAYMENT);
            assertThat(r.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(r.creditor()).as("the borrower gains a claim back").isEqualTo(ben);
            assertThat(r.debtor()).isEqualTo(ama);
            assertThat(r.amountMinor()).isEqualTo(10_000);
            assertThat(r.currency()).isEqualTo(USD);
            assertThat(r.settlesTransactionId()).isEqualTo(loan);
            assertThat(r.initiatedBy()).isEqualTo(ben);
            assertThat(r.openingBalance()).isFalse();
        }

        @Test
        void isDatedTodayWhereTheBorrowerLives() {   // Ledger.Entry-12, CON-8
            LedgerTransaction r = recorded(submit(ben, repay(loan, "100.00", "USD")));
            assertThat(r.transactionDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        }

        @Test
        void changesNoBalanceAndAlertsTheLenderOnly() {   // T-Repay-2a, T-NPend-1a
            Map<String, Long> before = netPositions();
            LedgerTransaction r = recorded(submit(ben, repay(loan, "100.00", "USD")));

            assertThat(netPositions()).isEqualTo(before);
            assertThat(jdbc.sql("SELECT recipient_id, kind FROM notification WHERE transaction_id = :t")
                    .param("t", r.id())
                    .query((rs, n) -> List.<Object>of(rs.getObject(1, UUID.class), rs.getString(2))).list())
                    .containsExactly(List.of(ama, "pending_alert"));
            assertThat(jdbc.sql("SELECT count(*) FROM transaction_status_history WHERE transaction_id = :t")
                    .param("t", r.id()).query(Integer.class).single()).isOne();
        }

        @Test
        void hidesTheLoanFromTheListUntilItIsDecided() {   // T-Repay-8e
            recorded(submit(ben, repay(loan, "100.00", "USD")));
            assertThat(repayments.outstandingLoansOwedBy(ben)).isEmpty();
            assertThat(repayments.viewTransaction(ben, loan)).isEmpty();
        }

        @Test
        void aLoanWhoseRepaymentWasDeclinedCanBeRepaidAgain() {   // T-Repay-8e
            ledger.decline(ledger.repayment(loan));
            recorded(submit(ben, repay(loan, "100.00", "USD")));
        }

        @Test
        void aLoanWhoseRepaymentWasReversedCanBeRepaidAgain() {   // T-Repay-10a
            UUID first = ledger.approve(ledger.repayment(loan));
            ledger.approve(ledger.correction(first));
            recorded(submit(ben, repay(loan, "100.00", "USD")));
        }

        @Test
        void anXafLoanIsRepaidInWholeFrancs() {   // T-Entry-13a applied to repayments
            UUID francs = ledger.approvedLoan(ama, ben, 10_000, XAF);
            LedgerTransaction r = recorded(submit(ben, repay(francs, "10000", "XAF")));
            assertThat(r.amountMinor()).isEqualTo(10_000);
        }
    }

    // ---- refused: nothing is saved ------------------------------------------------

    @Nested
    class Refused {

        @Test
        void lessThanTheLoan() {   // T-Repay-5a
            assertThat(rejected(submit(ben, repay(loan, "60.00", "USD"))))
                    .containsExactly(Map.entry("amount", "ledger.repayment.amount.mismatch"));
            assertThat(repaymentsOfTheLoan()).isZero();
        }

        @Test
        void moreThanTheLoan() {   // T-Repay-7a
            assertThat(rejected(submit(ben, repay(loan, "120.00", "USD"))))
                    .containsEntry("amount", "ledger.repayment.amount.mismatch");
            assertThat(repaymentsOfTheLoan()).isZero();
        }

        @Test
        void anAmountThatIsNotANumber() {
            assertThat(rejected(submit(ben, repay(loan, "a hundred", "USD"))))
                    .containsEntry("amount", Amounts.NOT_A_NUMBER);
            assertThat(repaymentsOfTheLoan()).isZero();
        }

        @Test
        void anotherCurrency() {   // T-Repay-9a, through the service
            UUID francs = ledger.approvedLoan(ama, ben, 10_000, XAF);
            assertThat(rejected(submit(ben, repay(francs, "100.00", "USD"))))
                    .containsEntry("currency", "ledger.repayment.currency.mismatch");
        }

        @Test
        void anUnknownOrMissingCurrency() {
            assertThat(rejected(submit(ben, repay(loan, "100.00", "XYZ")))).containsKey("currency");
            assertThat(rejected(submit(ben, repay(loan, "100.00", null)))).containsKey("currency");
            assertThat(repaymentsOfTheLoan()).isZero();
        }

        @Test
        void aSettledLoan() {   // T-Repay-10b
            ledger.approve(ledger.repayment(loan));
            int before = repaymentsOfTheLoan();
            assertThat(rejected(submit(ben, repay(loan, "100.00", "USD"))))
                    .containsExactly(Map.entry("loan", "ledger.repayment.loan.notOutstanding"));
            assertThat(repaymentsOfTheLoan()).isEqualTo(before);
        }

        @Test
        void aReversedLoan() {   // T-Repay-10b
            ledger.approve(ledger.correction(loan));
            assertThat(rejected(submit(ben, repay(loan, "100.00", "USD"))))
                    .containsExactly(Map.entry("loan", "ledger.repayment.loan.notOutstanding"));
            assertThat(repaymentsOfTheLoan()).isZero();
        }

        @Test
        void aLoanThatAlreadyHasARepaymentPending() {   // T-Repay-8e, a second tab
            recorded(submit(ben, repay(loan, "100.00", "USD")));
            assertThat(rejected(submit(ben, repay(loan, "100.00", "USD"))))
                    .containsEntry("loan", "ledger.repayment.loan.notOutstanding");
            assertThat(repaymentsOfTheLoan()).isOne();
        }

        @Test
        void aLoanThatWasNeverApproved() {   // T-Repay-8a
            UUID pending = ledger.loan(ama, ben, 10_000, USD);
            assertThat(rejected(submit(ben, repay(pending, "100.00", "USD")))).containsKey("loan");
        }

        @Test
        void somethingThatIsNotALoan() {   // a hand-made POST naming a repayment
            UUID repayment = ledger.approve(ledger.repayment(ledger.approvedLoan(ben, ama, 500, USD)));
            assertThat(rejected(submit(ama, repay(repayment, "5.00", "USD")))).containsKey("loan");
        }

        @Test
        void theLenderRepayingTheirOwnLoan() {   // only the borrower repays
            assertThat(rejected(submit(ama, repay(loan, "100.00", "USD")))).containsKey("loan");
            assertThat(repaymentsOfTheLoan()).isZero();
        }

        @Test
        void someoneOutsideTheLoan() {   // T-Hist-7a in spirit: no access to others' loans
            assertThat(rejected(submit(cleo, repay(loan, "100.00", "USD")))).containsKey("loan");
            assertThat(repayments.viewTransaction(cleo, loan)).isEmpty();
            assertThat(repaymentsOfTheLoan()).isZero();
        }

        @Test
        void aDeactivatedBorrower() {   // Auth.Login-5
            jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :m").param("m", ben).update();
            assertThatThrownBy(() -> submit(ben, repay(loan, "100.00", "USD")))
                    .isInstanceOf(NotPermittedException.class);
            assertThatThrownBy(() -> repayments.outstandingLoansOwedBy(ben))
                    .isInstanceOf(NotPermittedException.class);
            assertThat(repaymentsOfTheLoan()).isZero();
        }
    }

    // ---- the two read methods -----------------------------------------------------

    @Test
    void theBorrowerSeesTheLoanAndTheLenderDoesNot() {   // T-Repay-1a
        assertThat(repayments.outstandingLoansOwedBy(ben)).extracting(LedgerTransaction::id).containsExactly(loan);
        assertThat(repayments.viewTransaction(ben, loan)).map(LedgerTransaction::id).contains(loan);
        assertThat(repayments.outstandingLoansOwedBy(ama)).isEmpty();
        assertThat(repayments.viewTransaction(ama, loan)).isEmpty();
        assertThat(repayments.viewTransaction(ben, UUID.randomUUID())).isEmpty();
    }

    // ---- retries and races ------------------------------------------------------------

    @Test
    void aRetryOfTheSameSubmissionIsRecordedOnce() {   // Ledger.Entry-14
        RepaymentRequest once = repay(loan, "100.00", "USD");
        LedgerTransaction first = recorded(submit(ben, once));

        Outcome again = submit(ben, once);
        assertThat(again).isEqualTo(new Outcome.Recorded(first, true));
        assertThat(repaymentsOfTheLoan()).isOne();
    }

    @Test
    void retriesArrivingTogetherAreRecordedOnceAndAllSayRecorded() throws Exception {   // T-Entry-14a
        for (int run = 0; run < 20; run++) {
            amaLentBenOneHundredDollars();
            RepaymentRequest once = repay(loan, "100.00", "USD");
            List<Outcome> outcomes = together(4, () -> submit(ben, once));

            assertThat(outcomes).as("run %d: a double-tap must not tell Ben the loan is gone", run)
                    .allMatch(o -> o instanceof Outcome.Recorded);
            assertThat(outcomes.stream().map(o -> ((Outcome.Recorded) o).transaction().id()).distinct()).hasSize(1);
            assertThat(repaymentsOfTheLoan()).isOne();
        }
    }

    @Test
    void twoTabsSubmittingAtOnceRecordOneRepayment() throws Exception {   // the loan lock
        for (int run = 0; run < 20; run++) {
            amaLentBenOneHundredDollars();
            List<Outcome> outcomes = together(2, () -> submit(ben, repay(loan, "100.00", "USD")));

            assertThat(outcomes).as("run %d", run).filteredOn(o -> o instanceof Outcome.Recorded).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o instanceof Outcome.Rejected).singleElement()
                    .isEqualTo(new Outcome.Rejected(Map.of("loan", "ledger.repayment.loan.notOutstanding")));
            assertThat(repaymentsOfTheLoan()).isOne();
        }
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
