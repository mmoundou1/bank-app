package com.moundou.bank.ledger;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.moundou.bank.support.LedgerFixtures.DATE;
import static com.moundou.bank.support.LedgerFixtures.EUR;
import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.XAF;
import static com.moundou.bank.support.LedgerFixtures.assertRejectedBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. MB-7: the rules that must live in the database, proven against real
 * Postgres with the migrations applied to an empty database first.
 *
 * Every rejection is asserted by constraint name (see
 * {@link LedgerFixtures#assertRejectedBy}), so each test fails if a different rule
 * catches the row than the one it claims to prove.
 */
class LedgerSchemaIT {

    private static JdbcClient jdbc;
    private LedgerFixtures ledger;
    private UUID ama;
    private UUID ben;
    private UUID cho;

    @BeforeAll
    static void migrate() {
        TestDatabase.migrateFromEmpty();   // MB-7 done-when: migrations apply cleanly to an empty database
        jdbc = JdbcClient.create(TestDatabase.dataSource());
    }

    @BeforeEach
    void freshLedger() {
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        ama = ledger.member("Ama");
        ben = ledger.member("Ben");
        cho = ledger.member("Cho");
    }

    private NewTransaction loanRow(UUID creditor, UUID debtor, long amount, UUID initiatedBy) {
        return new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN, creditor, debtor, amount, USD,
                DATE, null, initiatedBy, null, null, false, UUID.randomUUID());
    }

    @Nested
    class Migrations {

        @Test
        void createTheFiveTablesTheTwoViewsAndTheSessionTables() {
            List<String> relations = jdbc.sql("""
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'
                     ORDER BY table_name""").query(String.class).list();
            assertThat(relations).containsExactly(
                    "allowed_email", "member", "member_net", "notification", "pair_net",
                    "spring_session", "spring_session_attributes",
                    "transaction", "transaction_status_history");
        }

        /**
         * T-Sec-4a and INT-2: no credential and no balance on member. Pinning the exact
         * column list means any new column has to be added here on purpose.
         */
        @Test
        void memberHasNoCredentialOrBalanceColumn() {
            List<String> columns = jdbc.sql("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = current_schema() AND table_name = 'member'
                     ORDER BY ordinal_position""").query(String.class).list();
            assertThat(columns).containsExactly(
                    "member_id", "idp_subject", "email", "display_name", "time_zone",
                    "role", "status", "created_at");
        }

        /** CON-5, INT-1, ADR-013: no numeric, decimal or float anywhere in the schema. */
        @Test
        void noColumnAnywhereIsDecimalOrFloatingPoint() {
            List<String> offenders = jdbc.sql("""
                    SELECT table_name || '.' || column_name FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND data_type IN ('numeric', 'real', 'double precision', 'money')""")
                    .query(String.class).list();
            assertThat(offenders).isEmpty();
        }

        /** ADR-012: a calendar date, never a timestamp. */
        @Test
        void transactionDateIsADate() {
            String type = jdbc.sql("""
                    SELECT data_type FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = 'transaction' AND column_name = 'transaction_date'""")
                    .query(String.class).single();
            assertThat(type).isEqualTo("date");
        }
    }

    @Nested
    class TransactionChecks {

        @Test
        void amountMustBePositive() {   // Ledger.Entry-2
            assertRejectedBy("transaction_amount_positive", () -> ledger.insert(loanRow(ama, ben, 0, ama)));
            assertRejectedBy("transaction_amount_positive", () -> ledger.insert(loanRow(ama, ben, -100, ama)));
        }

        @Test
        void partiesMustDiffer() {   // Ledger.Entry-3
            assertRejectedBy("transaction_distinct_parties", () -> ledger.insert(loanRow(ama, ama, 100, ama)));
        }

        @Test
        void onlyUsdXafAndEurAreAccepted() {   // Ledger.Entry-13
            for (var ok : List.of(USD, XAF, EUR)) {
                ledger.loan(ama, ben, 100, ok);
            }
            assertRejectedBy("transaction_currency_check", () -> ledger.loan(ama, ben, 100,
                    java.util.Currency.getInstance("GBP")));
        }

        @Test
        void onlyAPartyMayInitiate() {
            assertRejectedBy("transaction_initiator_is_party", () -> ledger.insert(loanRow(ama, ben, 100, cho)));
        }

        /** MB-7 done-when: an opening balance of any kind other than loan is rejected (INT-9). */
        @Test
        void onlyALoanMayBeAnOpeningBalance() {
            UUID loan = ledger.approvedLoan(ama, ben, 5_000, USD);
            ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN, ama, ben, 5_000, USD,
                    DATE, null, ama, null, null, true, UUID.randomUUID()));   // a loan may

            assertRejectedBy("transaction_opening_balance_is_loan", () -> ledger.insert(new NewTransaction(
                    UUID.randomUUID(), TransactionKind.REPAYMENT, ben, ama, 5_000, USD,
                    DATE, null, ben, loan, null, true, UUID.randomUUID())));
            assertRejectedBy("transaction_opening_balance_is_loan", () -> ledger.insert(new NewTransaction(
                    UUID.randomUUID(), TransactionKind.COMPENSATING, ben, ama, 5_000, USD,
                    DATE, null, ben, null, loan, true, UUID.randomUUID())));
        }

        @Test
        void aRepaymentMustNameItsLoanAndOnlyARepaymentMay() {
            UUID loan = ledger.approvedLoan(ama, ben, 5_000, USD);
            assertRejectedBy("transaction_repayment_settles", () -> ledger.insert(new NewTransaction(
                    UUID.randomUUID(), TransactionKind.REPAYMENT, ben, ama, 5_000, USD,
                    DATE, null, ben, null, null, false, UUID.randomUUID())));
            assertRejectedBy("transaction_repayment_settles", () -> ledger.insert(new NewTransaction(
                    UUID.randomUUID(), TransactionKind.LOAN, ama, ben, 5_000, USD,
                    DATE, null, ama, loan, null, false, UUID.randomUUID())));
        }

        @Test
        void aCompensatingEntryMustNameItsTargetAndOnlyACompensatingEntryMay() {
            UUID loan = ledger.approvedLoan(ama, ben, 5_000, USD);
            assertRejectedBy("transaction_compensating_corrects", () -> ledger.insert(new NewTransaction(
                    UUID.randomUUID(), TransactionKind.COMPENSATING, ben, ama, 5_000, USD,
                    DATE, null, ben, null, null, false, UUID.randomUUID())));
            assertRejectedBy("transaction_compensating_corrects", () -> ledger.insert(new NewTransaction(
                    UUID.randomUUID(), TransactionKind.LOAN, ama, ben, 5_000, USD,
                    DATE, null, ama, null, loan, false, UUID.randomUUID())));
        }

        @Test
        void decidedAtIsSetExactlyWhenNoLongerPending() {
            UUID loan = ledger.loan(ama, ben, 100, USD);
            assertThat(ledger.repository().findById(loan).orElseThrow().decidedAt()).isNull();
            assertRejectedBy("transaction_decided_at_check", () -> jdbc.sql(
                    "UPDATE transaction SET status = 'approved' WHERE transaction_id = :id")
                    .param("id", loan).update());
            ledger.approve(loan);
            assertThat(ledger.repository().findById(loan).orElseThrow().decidedAt()).isNotNull();
        }

        @Test
        void aDeclineReasonOnlyOnADeclinedRow() {
            UUID loan = ledger.loan(ama, ben, 100, USD);
            assertRejectedBy("transaction_decline_reason_check", () -> jdbc.sql(
                    "UPDATE transaction SET decline_reason = 'x' WHERE transaction_id = :id")
                    .param("id", loan).update());
        }
    }

    /** T-Repay-9a and INT-8: the composite foreign keys pin the currency of a reference. */
    @Nested
    class SameCurrencyReferences {

        @Test
        void aRepaymentInAnotherCurrencyIsRejected() {
            UUID loan = ledger.approvedLoan(ama, ben, 10_000, XAF);
            assertRejectedBy("transaction_settles_fk", () -> ledger.repayment(loan, 10_000, EUR));
            assertThat(ledger.repayment(loan, 10_000, XAF)).isNotNull();   // same currency is fine
        }

        @Test
        void aCorrectionInAnotherCurrencyIsRejected() {
            UUID loan = ledger.approvedLoan(ama, ben, 2_500, EUR);
            assertRejectedBy("transaction_corrects_fk", () -> ledger.correction(loan, USD));
            assertThat(ledger.correction(loan, EUR)).isNotNull();
        }

        @Test
        void aReferenceToNoRowAtAllIsRejected() {
            assertRejectedBy("transaction_settles_fk", () -> ledger.insert(new NewTransaction(
                    UUID.randomUUID(), TransactionKind.REPAYMENT, ben, ama, 100, USD,
                    DATE, null, ben, UUID.randomUUID(), null, false, UUID.randomUUID())));
        }
    }

    /** T-Hist-11c and INT-6: at most one approved correction, counted by a partial index. */
    @Nested
    class OneApprovedCorrection {

        @Test
        void aDeclinedCorrectionDoesNotBlockALaterOne_butASecondApprovedOneIsRejected() {
            UUID loan = ledger.approvedLoan(ama, ben, 7_000, USD);

            ledger.decline(ledger.correction(loan));
            ledger.cancel(ledger.correction(loan));
            UUID accepted = ledger.approve(ledger.correction(loan));   // allowed after declined and cancelled ones

            UUID second = ledger.correction(loan);                      // a pending one may exist...
            assertRejectedBy("transaction_one_approved_correction", () -> ledger.approve(second));  // ...but not be approved

            assertThat(ledger.status(accepted)).isEqualTo(TransactionStatus.APPROVED);
            assertThat(ledger.status(second)).isEqualTo(TransactionStatus.PENDING);
        }
    }

    /** Ledger.Entry-14, Technical Design D1: a retried submission is recorded once. */
    @Nested
    class SubmissionKey {

        @Test
        void aDuplicateKeyFromTheSameMemberIsRejected() {
            UUID key = UUID.randomUUID();
            ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN, ama, ben, 100, USD,
                    DATE, null, ama, null, null, false, key));
            assertRejectedBy("transaction_submission_key", () -> ledger.insert(new NewTransaction(
                    UUID.randomUUID(), TransactionKind.LOAN, ama, ben, 100, USD,
                    DATE, null, ama, null, null, false, key)));

            // The service answers the retry with the row the first attempt created.
            assertThat(ledger.repository().findBySubmissionKey(ama, key)).isPresent();
        }

        @Test
        void theSameKeyFromAnotherMemberIsAnotherSubmission() {
            UUID key = UUID.randomUUID();
            ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN, ama, ben, 100, USD,
                    DATE, null, ama, null, null, false, key));
            ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN, ben, cho, 100, USD,
                    DATE, null, ben, null, null, false, key));
        }
    }

    @Nested
    class Members {

        @Test
        void emailAndSubjectAreUnique_andEmailIsStoredLowerCase() {
            jdbc.sql("""
                    INSERT INTO member (member_id, idp_subject, email, display_name, time_zone, role)
                    VALUES (:id, 's-1', 'dee@example.com', 'Dee', 'Europe/Paris', 'family_member')""")
                    .param("id", UUID.randomUUID()).update();
            assertRejectedBy("member_email_key", () -> jdbc.sql("""
                    INSERT INTO member (member_id, idp_subject, email, display_name, time_zone, role)
                    VALUES (:id, 's-2', 'dee@example.com', 'Dee', 'Europe/Paris', 'family_member')""")
                    .param("id", UUID.randomUUID()).update());
            assertRejectedBy("member_idp_subject_key", () -> jdbc.sql("""
                    INSERT INTO member (member_id, idp_subject, email, display_name, time_zone, role)
                    VALUES (:id, 's-1', 'eve@example.com', 'Eve', 'Europe/Paris', 'family_member')""")
                    .param("id", UUID.randomUUID()).update());
            assertRejectedBy("member_email_lower", () -> jdbc.sql("""
                    INSERT INTO member (member_id, idp_subject, email, display_name, time_zone, role)
                    VALUES (:id, 's-3', 'Fay@example.com', 'Fay', 'Europe/Paris', 'family_member')""")
                    .param("id", UUID.randomUUID()).update());
        }

        /** Data-7: removing access removes nothing else. */
        @Test
        void removingAnAllowListEntryLeavesTheMemberAndTheirLedger() {
            String email = jdbc.sql("SELECT email FROM member WHERE member_id = :id")
                    .param("id", ama).query(String.class).single();
            jdbc.sql("INSERT INTO allowed_email (email) VALUES (:e)").param("e", email).update();  // bootstrap row: no author
            UUID loan = ledger.approvedLoan(ama, ben, 100, USD);

            jdbc.sql("DELETE FROM allowed_email WHERE email = :e").param("e", email).update();

            assertThat(jdbc.sql("SELECT count(*) FROM member WHERE member_id = :id")
                    .param("id", ama).query(Integer.class).single()).isOne();
            assertThat(ledger.repository().findById(loan)).isPresent();
        }
    }

    /** Technical Design 3: both views sum approved rows only, per currency. */
    @Nested
    class BalanceViews {

        @Test
        void countOnlyApprovedRows_perCurrency_andSumToZero() {
            UUID loan = ledger.approvedLoan(ama, ben, 10_000, USD);   // Ben owes Ama $100.00
            ledger.approvedLoan(ben, ama, 2_500, USD);                // Ama owes Ben $25.00
            ledger.approvedLoan(cho, ama, 5_000, XAF);                // Ama owes Cho 5,000 XAF
            ledger.loan(ama, ben, 99_999, USD);                       // pending: ignored
            ledger.decline(ledger.loan(ama, ben, 88_888, USD));       // declined: ignored
            ledger.decline(ledger.repayment(loan));                   // declined repayment: ignored

            Map<String, Long> net = jdbc.sql("""
                    SELECT m.display_name || ' ' || n.currency AS k, n.net_minor
                      FROM member_net n JOIN member m USING (member_id)""")
                    .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                    .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(net).containsOnly(
                    Map.entry("Ama USD", 7_500L), Map.entry("Ben USD", -7_500L),
                    Map.entry("Ama XAF", -5_000L), Map.entry("Cho XAF", 5_000L));

            // INT-7: in each currency, net positions sum to zero.
            assertThat(jdbc.sql("SELECT count(*) FROM (SELECT currency FROM member_net GROUP BY currency"
                    + " HAVING SUM(net_minor) <> 0) x").query(Integer.class).single()).isZero();

            long amaBenUsd = jdbc.sql("""
                    SELECT CASE WHEN member_a = :ama THEN a_net_minor ELSE -a_net_minor END
                      FROM pair_net
                     WHERE LEAST(:ama, :ben) = member_a AND GREATEST(:ama, :ben) = member_b
                       AND currency = 'USD'""")
                    .param("ama", ama).param("ben", ben).query(Long.class).single();
            assertThat(amaBenUsd).as("Ama's side of the Ama-Ben USD balance").isEqualTo(7_500L);
        }
    }
}
