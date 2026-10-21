package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.moundou.bank.ledger.HistoryDirection.CORRECTION;
import static com.moundou.bank.ledger.HistoryDirection.YOU_BORROWED;
import static com.moundou.bank.ledger.HistoryDirection.YOU_LENT;
import static com.moundou.bank.ledger.HistoryDirection.YOU_REPAID;
import static com.moundou.bank.ledger.HistoryDirection.YOU_WERE_REPAID;
import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.XAF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slow suite. Turning a member's transactions into history cards (MB-14), through the
 * real Spring context against Postgres. Which rows, and in what order, is
 * TransactionRepository.historyFor's job and is tested in HistoryQueriesIT; here each
 * row has to become the right card for the member looking at it.
 *
 * Ama lends to Ben throughout. Every row is read from both sides, because the same row
 * is a different sentence for each of its two members.
 */
@SpringBootTest
class HistoryServiceIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.migrateFromEmpty();
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired HistoryService history;
    @Autowired DataSource dataSource;

    JdbcClient jdbc;
    LedgerFixtures ledger;
    UUID ama;
    UUID ben;

    @BeforeEach
    void freshLedger() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        ama = ledger.member("Ama");
        ben = ledger.member("Ben");
    }

    @Test
    void aLoanReadsAsLentForTheLenderAndBorrowedForTheBorrower() {                // T-Hist-6a
        ledger.approvedLoan(ama, ben, 2000, USD);

        HistoryEntry amaSees = only(history.historyFor(ama));
        assertThat(amaSees.direction()).isEqualTo(YOU_LENT);
        assertThat(amaSees.otherPartyName()).isEqualTo("Ben");

        HistoryEntry benSees = only(history.historyFor(ben));
        assertThat(benSees.direction()).isEqualTo(YOU_BORROWED);
        assertThat(benSees.otherPartyName()).isEqualTo("Ama");
    }

    @Test
    void aRepaymentReadsAsRepaidForTheBorrowerWhoIsItsCreditor() {                // T-Hist-6a, D2
        // The trap: on a repayment the creditor is the borrower, the one who paid.
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID repaid = ledger.approve(ledger.repayment(lent));

        assertThat(entryFor(ben, repaid).direction()).isEqualTo(YOU_REPAID);
        assertThat(entryFor(ben, repaid).otherPartyName()).isEqualTo("Ama");
        assertThat(entryFor(ama, repaid).direction()).isEqualTo(YOU_WERE_REPAID);
        assertThat(entryFor(ama, repaid).otherPartyName()).isEqualTo("Ben");
    }

    @Test
    void aCorrectionOfALoanCarriesTheLoansSentenceAndDateForEachViewer() {
        LocalDate lentOn = LocalDate.of(2026, 9, 3);
        UUID lent = ledger.approve(ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN,
                ama, ben, 2000, USD, lentOn, null, ama, null, null, false, UUID.randomUUID())));
        UUID reversal = ledger.correction(lent);

        HistoryEntry benSees = entryFor(ben, reversal);
        assertThat(benSees.direction()).isEqualTo(CORRECTION);
        assertThat(benSees.targetDirection()).isEqualTo(YOU_BORROWED);
        assertThat(benSees.targetDate()).isEqualTo(lentOn);
        assertThat(benSees.otherPartyName()).isEqualTo("Ama");

        HistoryEntry amaSees = entryFor(ama, reversal);
        assertThat(amaSees.direction()).isEqualTo(CORRECTION);
        assertThat(amaSees.targetDirection()).isEqualTo(YOU_LENT);
        assertThat(amaSees.otherPartyName()).isEqualTo("Ben");
    }

    @Test
    void aCorrectionOfARepaymentCarriesTheRepaymentsSentence() {
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID repaid = ledger.approve(ledger.repayment(lent));
        UUID reversal = ledger.correction(repaid);

        assertThat(entryFor(ben, reversal).targetDirection()).isEqualTo(YOU_REPAID);
        assertThat(entryFor(ama, reversal).targetDirection()).isEqualTo(YOU_WERE_REPAID);
    }

    @Test
    void onlyCorrectionsCarryATarget() {
        UUID lent = ledger.approvedLoan(ama, ben, 2000, USD);
        UUID repaid = ledger.approve(ledger.repayment(lent));

        for (UUID id : List.of(lent, repaid)) {
            HistoryEntry entry = entryFor(ama, id);
            assertThat(entry.targetDirection()).isNull();
            assertThat(entry.targetDate()).isNull();
        }
    }

    @Test
    void recordedByYouIsTrueOnlyForTheMemberWhoRecordedIt() {
        UUID lent = ledger.loan(ama, ben, 2000, USD);                              // Ama recorded it

        assertThat(entryFor(ama, lent).recordedByYou()).isTrue();
        assertThat(entryFor(ben, lent).recordedByYou()).isFalse();
    }

    @Test
    void theRowsOwnFactsAreCopiedAcross() {                                       // T-Hist-3a, T-Hist-6a
        LocalDate on = LocalDate.of(2019, 6, 1);
        UUID opening = ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN,
                ama, ben, 150_000, XAF, on, "School fees", ama, null, null, true, UUID.randomUUID()));
        UUID declined = ledger.decline(ledger.loan(ben, ama, 500, USD));

        HistoryEntry pending = entryFor(ben, opening);
        assertThat(pending.amountMinor()).isEqualTo(150_000);
        assertThat(pending.currency()).isEqualTo(XAF);
        assertThat(pending.transactionDate()).isEqualTo(on);
        assertThat(pending.note()).isEqualTo("School fees");
        assertThat(pending.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(pending.decidedAt()).as("not decided yet").isNull();
        assertThat(pending.openingBalance()).isTrue();
        assertThat(pending.declineReason()).isNull();

        HistoryEntry refused = entryFor(ben, declined);
        assertThat(refused.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(refused.declineReason()).isEqualTo("not mine");
        assertThat(refused.decidedAt()).isNotNull();
        assertThat(refused.openingBalance()).isFalse();
    }

    @Test
    void cardsKeepTheNewestFirstOrderOfTheQuery() {                               // T-Hist-1a
        UUID older = ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN,
                ama, ben, 100, USD, LocalDate.of(2026, 9, 1), "older", ama, null, null, false, UUID.randomUUID()));
        UUID newer = ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN,
                ben, ama, 200, USD, LocalDate.of(2026, 10, 1), "newer", ben, null, null, false, UUID.randomUUID()));

        assertThat(history.historyFor(ama)).extracting(HistoryEntry::note).containsExactly("newer", "older");
    }

    @Test
    void aDeactivatedMembersNameStillAppearsOnTheirCards() {                     // T-Roles-7a
        ledger.approvedLoan(ama, ben, 2000, USD);
        jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :id").param("id", ben).update();

        assertThat(only(history.historyFor(ama)).otherPartyName()).isEqualTo("Ben");
    }

    @Test
    void aDeactivatedOrUnknownViewerIsRefused() {
        jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :id").param("id", ben).update();

        assertThatThrownBy(() -> history.historyFor(ben)).isInstanceOf(NotPermittedException.class);
        assertThatThrownBy(() -> history.historyFor(UUID.randomUUID())).isInstanceOf(NotPermittedException.class);
    }

    @Test
    void aMemberWithNoTransactionsGetsNoCards() {
        assertThat(history.historyFor(ama)).isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------

    private static HistoryEntry only(List<HistoryEntry> entries) {
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }

    /**
     * The card {@code viewer} sees for transaction {@code id}. HistoryEntry carries no id
     * yet (it arrives with journey 2), so the card is found by position: the query's
     * order is the card order, and the repository gives that order.
     */
    private HistoryEntry entryFor(UUID viewer, UUID id) {
        List<UUID> ids = ledger.repository().historyFor(viewer).stream().map(LedgerTransaction::id).toList();
        int position = ids.indexOf(id);
        assertThat(position).as("transaction in %s's history", viewer).isNotNegative();
        return history.historyFor(viewer).get(position);
    }
}
