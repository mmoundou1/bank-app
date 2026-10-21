package com.moundou.bank.ledger;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.XAF;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The query behind the history screen (MB-14): which transactions a member
 * sees, and in what order (Ledger.History-1, -2, -7). Turning the rows into cards is the
 * service's job and is tested there.
 */
class HistoryQueriesIT {

    private static JdbcClient jdbc;
    private LedgerFixtures ledger;
    private TransactionRepository repository;
    private UUID ama;
    private UUID ben;
    private UUID cleo;
    private UUID dede;

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
        dede = ledger.member("Dede");
    }

    @Test
    void everyTransactionWithEveryCounterpartyIsListedNewestFirst() {      // T-Hist-1a
        UUID oldest = loanOn(LocalDate.of(2026, 9, 1), ama, ben);          // Ama lent to Ben
        UUID middle = loanOn(LocalDate.of(2026, 9, 15), cleo, ama);        // Cleo lent to Ama
        UUID newest = loanOn(LocalDate.of(2026, 10, 1), ama, dede);

        assertThat(repository.historyFor(ama))
                .extracting(LedgerTransaction::id).containsExactly(newest, middle, oldest);
    }

    @Test
    void allFourStatusesAreListed() {                                      // T-Hist-2a
        UUID approved = ledger.approvedLoan(ama, ben, 100, USD);
        UUID pending = ledger.loan(ama, ben, 200, USD);
        UUID declined = ledger.decline(ledger.loan(ben, ama, 300, USD));
        UUID cancelled = ledger.cancel(ledger.loan(ama, ben, 400, USD));

        assertThat(repository.historyFor(ama))
                .extracting(LedgerTransaction::id)
                .containsExactlyInAnyOrder(approved, pending, declined, cancelled);
    }

    @Test
    void repaymentsAndCorrectionsAreListedAlongsideTheirLoans() {
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID repaid = ledger.approve(ledger.repayment(lent));
        UUID reversed = ledger.correction(repaid);

        assertThat(repository.historyFor(ben))
                .extracting(LedgerTransaction::id)
                .containsExactlyInAnyOrder(lent, repaid, reversed);
    }

    @Test
    void aCorrectionsTargetIsAlwaysInTheSameMembersHistory() {
        // The service finds a correction's target in this same list, with no second
        // query. That works because a correction is between the same two members.
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        ledger.correction(lent);

        for (UUID viewer : List.of(ama, ben)) {
            List<LedgerTransaction> history = repository.historyFor(viewer);
            List<UUID> ids = history.stream().map(LedgerTransaction::id).toList();
            assertThat(history)
                    .filteredOn(t -> t.correctsTransactionId() != null)
                    .allSatisfy(c -> assertThat(ids).contains(c.correctsTransactionId()));
        }
    }

    @Test
    void otherMembersTransactionsWithEachOtherAreNotListed() {             // T-Hist-7a
        UUID mine = ledger.approvedLoan(ama, ben, 100, USD);
        ledger.approvedLoan(cleo, dede, 5_000, XAF);                       // Cleo and Dede only
        ledger.loan(dede, cleo, 700, USD);

        assertThat(repository.historyFor(ama))
                .extracting(LedgerTransaction::id).containsExactly(mine);
    }

    @Test
    void onTheSameDateTheLaterRecordedComesFirst() {
        LocalDate day = LocalDate.of(2026, 10, 5);
        UUID first = loanOn(day, ama, ben);
        UUID second = loanOn(day, ben, ama);
        jdbc.sql("UPDATE transaction SET created_at = now() - interval '1 hour' WHERE transaction_id = :id")
                .param("id", first).update();

        assertThat(repository.historyFor(ama))
                .extracting(LedgerTransaction::id).containsExactly(second, first);
    }

    @Test
    void rowsRecordedAtTheSameMomentKeepTheSameOrderOnEveryLoad() {
        LocalDate day = LocalDate.of(2026, 10, 5);
        UUID a = loanOn(day, ama, ben);
        UUID b = loanOn(day, ama, ben);
        UUID c = loanOn(day, ama, ben);
        jdbc.sql("UPDATE transaction SET created_at = timestamptz '2026-10-05 12:00:00+00'").update();

        // Postgres orders UUIDs byte by byte, which is the order of their text form.
        // UUID.compareTo would not do: it compares signed numbers and disagrees.
        List<UUID> byIdDescending = List.of(a, b, c).stream()
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        for (int load = 0; load < 5; load++) {
            assertThat(repository.historyFor(ama))
                    .extracting(LedgerTransaction::id).containsExactlyElementsOf(byIdDescending);
        }
    }

    @Test
    void aMemberWithNoTransactionsHasAnEmptyHistory() {
        ledger.approvedLoan(ama, ben, 100, USD);
        assertThat(repository.historyFor(cleo)).isEmpty();
    }

    /** A pending USD loan dated {@code date}: {@code lender} is creditor. */
    private UUID loanOn(LocalDate date, UUID lender, UUID borrower) {
        return ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN, lender, borrower,
                1000, USD, date, null, lender, null, null, false, UUID.randomUUID()));
    }
}
