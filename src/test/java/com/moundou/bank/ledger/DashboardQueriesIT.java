package com.moundou.bank.ledger;

import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.ledger.TransactionRepository.NetPosition;
import com.moundou.bank.ledger.TransactionRepository.OutstandingLoan;
import com.moundou.bank.ledger.TransactionRepository.PairNet;
import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.moundou.bank.support.LedgerFixtures.EUR;
import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.XAF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Slow suite. The queries behind Home and People (MB-15, MB-17; Technical Design 14.3).
 * The screens' rules (DT-1 to DT-3, sort orders, words) are DashboardServiceIT's; this
 * class checks only what each query returns.
 *
 * Every balance is read from both members' sides at least once: a member id and a
 * transaction id are both UUIDs, and pair_net stores each pair from one side only, so
 * a sign turned the wrong way would still compile (the MB-14 lesson).
 */
class DashboardQueriesIT {

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
    class NetPositions {

        @Test
        void lendsCountForTheLenderAndBorrowsAgainst() {                       // T-Net-1a
            ledger.approvedLoan(ama, ben, 10_000, USD);
            ledger.approvedLoan(ama, cleo, 5_000, USD);
            ledger.approvedLoan(ben, ama, 3_000, USD);

            assertThat(repository.netPositions(ama)).containsExactly(new NetPosition(USD, 12_000));
            assertThat(repository.netPositions(ben)).containsExactly(new NetPosition(USD, -7_000));
        }

        @Test
        void aMemberWithNoApprovedTransactionHasNoPositionAtAll() {             // T-Net-1b, Model 6 Q1
            ledger.loan(ama, ben, 2_000, USD);                                  // pending only

            assertThat(repository.netPositions(ama)).isEmpty();
            assertThat(repository.netPositions(ben)).isEmpty();
        }

        @Test
        void eachCurrencyStandsAloneAndIsNeverAddedToAnother() {               // T-Net-1c
            ledger.approvedLoan(ama, ben, 2_000, USD);
            ledger.approvedLoan(ben, ama, 50_000, XAF);

            assertThat(repository.netPositions(ama))
                    .containsExactly(new NetPosition(USD, 2_000), new NetPosition(XAF, -50_000));
        }

        @Test
        void onlyApprovedTransactionsCount() {                                  // T-Net-2a
            ledger.approvedLoan(ama, ben, 1_000, USD);
            ledger.loan(ama, ben, 50_000, USD);
            ledger.decline(ledger.loan(ama, ben, 20_000, USD));
            ledger.cancel(ledger.loan(ama, ben, 30_000, USD));

            assertThat(repository.netPositions(ama)).containsExactly(new NetPosition(USD, 1_000));
        }

        @Test
        void aCurrencyThatHasComeBackToZeroIsStillThere() {                     // Model 6 Q2: "All square"
            UUID lent = ledger.approvedLoan(ama, ben, 2_000, EUR);
            ledger.approve(ledger.repayment(lent));

            assertThat(repository.netPositions(ama)).containsExactly(new NetPosition(EUR, 0));
        }
    }

    @Nested
    class OutstandingLoans {

        @Test
        void exactlyTheOutstandingLoansAreListedInBothDirections() {          // T-Net-3b
            UUID made = ledger.approvedLoan(ama, ben, 1_000, USD);
            UUID received = ledger.approvedLoan(cleo, ama, 2_000, USD);
            UUID beingRepaid = ledger.approvedLoan(ama, cleo, 3_000, USD);
            ledger.repayment(beingRepaid);                                      // pending: still Outstanding
            UUID settled = ledger.approvedLoan(ama, ben, 4_000, USD);
            ledger.approve(ledger.repayment(settled));
            UUID reversed = ledger.approvedLoan(ben, ama, 5_000, USD);
            ledger.approve(ledger.correction(reversed));
            ledger.loan(ama, ben, 6_000, USD);
            ledger.decline(ledger.loan(ama, ben, 7_000, USD));
            ledger.cancel(ledger.loan(ben, ama, 8_000, USD));

            assertThat(repository.outstandingLoansOf(ama))
                    .extracting(o -> o.loan().id(), OutstandingLoan::repaymentPending)
                    .containsExactlyInAnyOrder(
                            tuple(made, false), tuple(received, false), tuple(beingRepaid, true));
        }

        @Test
        void aLoanWhoseRepaymentWasUndoneIsOutstandingAgain() {                // T-Repay-10a, L4
            UUID lent = ledger.approvedLoan(ama, ben, 1_000, USD);
            UUID repaid = ledger.approve(ledger.repayment(lent));
            ledger.approve(ledger.correction(repaid));

            assertThat(repository.outstandingLoansOf(ben)).extracting(o -> o.loan().id()).containsExactly(lent);
        }

        @Test
        void otherMembersLoansAreNeverListed() {                                // SEC-2
            ledger.approvedLoan(ben, cleo, 1_000, USD);

            assertThat(repository.outstandingLoansOf(ama)).isEmpty();
        }

        @Test
        void agreesWithLoanLifecycleOnEveryLoan() {                             // Technical Design 14.3
            List<UUID> loans = new ArrayList<>();
            loans.add(ledger.approvedLoan(ama, ben, 1_000, USD));               // Outstanding
            UUID settled = ledger.approvedLoan(ama, ben, 1_000, USD);
            ledger.approve(ledger.repayment(settled));
            loans.add(settled);                                                 // Settled
            UUID reversed = ledger.approvedLoan(ben, ama, 1_000, XAF);
            ledger.approve(ledger.correction(reversed));
            loans.add(reversed);                                                // Reversed
            UUID again = ledger.approvedLoan(ama, cleo, 1_000, EUR);
            ledger.approve(ledger.correction(ledger.approve(ledger.repayment(again))));
            loans.add(again);                                                   // Outstanding again (L4)
            UUID pendingRepayment = ledger.approvedLoan(cleo, ama, 1_000, USD);
            ledger.repayment(pendingRepayment);
            loans.add(pendingRepayment);                                        // Outstanding
            UUID declinedRepayment = ledger.approvedLoan(cleo, ama, 1_000, USD);
            ledger.decline(ledger.repayment(declinedRepayment));
            loans.add(declinedRepayment);                                       // Outstanding
            UUID pendingCorrection = ledger.approvedLoan(ama, ben, 1_000, USD);
            ledger.correction(pendingCorrection);
            loans.add(pendingCorrection);                                       // Outstanding
            loans.add(ledger.loan(ama, ben, 1_000, USD));                       // not approved

            List<UUID> listed = new ArrayList<>();
            for (UUID member : List.of(ama, ben, cleo)) {
                repository.outstandingLoansOf(member).forEach(o -> listed.add(o.loan().id()));
            }
            for (UUID loan : loans) {
                boolean outstanding = repository.loanLifecycle(loan) == LoanLifecycle.OUTSTANDING;
                assertThat(listed.contains(loan))
                        .as("loan %s: listed exactly when loanLifecycle says Outstanding", loan)
                        .isEqualTo(outstanding);
            }
        }

        @Test
        void activeLoansAddUpToTheNetPositionInEachCurrency() {                // T-Net-3b, Model 6 RT-2
            ledger.approvedLoan(ama, ben, 10_000, USD);
            ledger.approvedLoan(ben, ama, 2_500, USD);
            UUID settled = ledger.approvedLoan(cleo, ama, 7_000, USD);
            ledger.approve(ledger.repayment(settled));
            UUID reversed = ledger.approvedLoan(ama, cleo, 9_000, USD);
            ledger.approve(ledger.correction(reversed));
            UUID again = ledger.approvedLoan(cleo, ama, 40_000, XAF);
            ledger.approve(ledger.correction(ledger.approve(ledger.repayment(again))));
            ledger.approvedLoan(ama, ben, 300, EUR);

            for (UUID member : List.of(ama, ben, cleo)) {
                Map<Currency, Long> fromLoans = new HashMap<>();
                for (OutstandingLoan o : repository.outstandingLoansOf(member)) {
                    long signed = o.loan().creditor().equals(member) ? o.loan().amountMinor() : -o.loan().amountMinor();
                    fromLoans.merge(o.loan().currency(), signed, Long::sum);
                }
                for (NetPosition p : repository.netPositions(member)) {
                    assertThat(fromLoans.getOrDefault(p.currency(), 0L))
                            .as("member %s, %s", member, p.currency())
                            .isEqualTo(p.netMinor());
                }
            }
        }
    }

    @Nested
    class PairNets {

        @Test
        void eachSideSeesTheBalanceFromItsOwnSide() {                           // T-Cpty-1a, Model 6 RT-3
            ledger.approvedLoan(ama, ben, 2_000, USD);
            ledger.approvedLoan(ben, ama, 500, USD);

            assertThat(repository.pairNetsFor(ama)).containsExactly(new PairNet(ben, USD, 1_500));
            assertThat(repository.pairNetsFor(ben)).containsExactly(new PairNet(ama, USD, -1_500));
        }

        @Test
        void bothOrdersOfTheIdsGiveTheSameAnswer() {
            // pair_net keeps the lower id as member_a. Whichever of the two that is, the
            // lender must see a positive balance and the borrower a negative one.
            UUID low = new UUID(0, 1);
            UUID high = new UUID(Long.MAX_VALUE, Long.MAX_VALUE);
            insertMember(low, "Low");
            insertMember(high, "High");
            ledger.approvedLoan(high, low, 700, USD);                           // the higher id lends
            ledger.approvedLoan(low, high, 50, XAF);                            // the lower id lends

            assertThat(repository.pairNetsFor(high)).containsExactlyInAnyOrder(
                    new PairNet(low, USD, 700), new PairNet(low, XAF, -50));
            assertThat(repository.pairNetsFor(low)).containsExactlyInAnyOrder(
                    new PairNet(high, USD, -700), new PairNet(high, XAF, 50));
        }

        @Test
        void aPairBackAtZeroIsStillReturned() {                                 // T-Cpty-3a; DT-3 is the caller's
            UUID lent = ledger.approvedLoan(ama, ben, 2_000, USD);
            ledger.approve(ledger.repayment(lent));

            assertThat(repository.pairNetsFor(ama)).containsExactly(new PairNet(ben, USD, 0));
        }

        @Test
        void onlyPairsTheMemberBelongsTo() {                                    // SEC-2
            ledger.approvedLoan(ben, cleo, 2_000, USD);
            ledger.approvedLoan(ama, cleo, 100, USD);

            assertThat(repository.pairNetsFor(ama)).containsExactly(new PairNet(cleo, USD, 100));
        }

        @Test
        void aDeactivatedMembersBalanceIsStillReturned() {                      // T-Cpty-1b, Auth.Roles-7
            ledger.approvedLoan(ama, ben, 2_000, USD);
            ledger.deactivate(ben);

            assertThat(repository.pairNetsFor(ama)).containsExactly(new PairNet(ben, USD, 2_000));
        }

        @Test
        void balancesWithEachPersonAddUpToTheNetPosition() {                   // T-Cpty-1b, Model 6 RT-3
            ledger.approvedLoan(ama, ben, 2_000, USD);
            ledger.approvedLoan(cleo, ama, 3_500, USD);
            UUID settled = ledger.approvedLoan(ama, cleo, 900, XAF);
            ledger.approve(ledger.repayment(settled));
            ledger.approvedLoan(ben, ama, 1_000, XAF);

            for (UUID member : List.of(ama, ben, cleo)) {
                Map<Currency, Long> fromPairs = new HashMap<>();
                repository.pairNetsFor(member).forEach(p -> fromPairs.merge(p.currency(), p.netMinor(), Long::sum));
                for (NetPosition p : repository.netPositions(member)) {
                    assertThat(fromPairs.get(p.currency())).as("member %s, %s", member, p.currency()).isEqualTo(p.netMinor());
                }
            }
        }
    }

    @Nested
    class ApprovedBetween {

        @Test
        void approvedTransactionsOfEveryKindBetweenTheTwoOnly() {             // Dashboard.Counterparty-2
            UUID loan = ledger.approvedLoan(ama, ben, 2_000, USD);
            UUID repayment = ledger.approve(ledger.repayment(loan));
            UUID borrowed = ledger.approvedLoan(ben, ama, 300, XAF);
            UUID correction = ledger.approve(ledger.correction(borrowed));
            ledger.loan(ama, ben, 50, USD);                                     // pending
            ledger.decline(ledger.loan(ben, ama, 60, USD));                     // declined
            ledger.approvedLoan(ama, cleo, 70, USD);                            // someone else

            assertThat(repository.approvedBetween(ama, ben)).extracting(LedgerTransaction::id)
                    .containsExactlyInAnyOrder(loan, repayment, borrowed, correction);
            assertThat(repository.approvedBetween(ben, ama)).extracting(LedgerTransaction::id)
                    .containsExactlyInAnyOrderElementsOf(
                            repository.approvedBetween(ama, ben).stream().map(LedgerTransaction::id).toList());
        }

        @Test
        void theirEffectsAddUpToTheBalanceWithThatPerson() {                   // T-Cpty-2a, Model 6 RT-4
            ledger.approvedLoan(ama, ben, 2_000, USD);
            UUID second = ledger.approvedLoan(ben, ama, 700, USD);
            ledger.approve(ledger.repayment(second));
            UUID third = ledger.approvedLoan(ama, ben, 400, USD);
            ledger.approve(ledger.correction(third));
            ledger.approvedLoan(ben, ama, 15_000, XAF);

            Map<Currency, Long> effects = new HashMap<>();
            for (LedgerTransaction t : repository.approvedBetween(ama, ben)) {
                effects.merge(t.currency(), t.creditor().equals(ama) ? t.amountMinor() : -t.amountMinor(), Long::sum);
            }
            Map<Currency, Long> balances = new HashMap<>();
            repository.pairNetsFor(ama).forEach(p -> balances.put(p.currency(), p.netMinor()));

            assertThat(effects).isEqualTo(balances).containsEntry(USD, 2_000L).containsEntry(XAF, -15_000L);
        }
    }

    @Test
    void membersIncludesDeactivatedMembersAndSaysWhoIsActive() {               // Model 6 DT-3
        ledger.deactivate(cleo);

        Map<UUID, Member> members = new MemberDirectory(jdbc).members();

        assertThat(members).hasSize(3);
        assertThat(members.get(ama)).extracting(Member::displayName, Member::active).containsExactly("Ama", true);
        assertThat(members.get(cleo)).extracting(Member::displayName, Member::active).containsExactly("Cleo", false);
    }

    private void insertMember(UUID id, String name) {
        jdbc.sql("""
                INSERT INTO member (member_id, idp_subject, email, display_name, time_zone, role)
                VALUES (:id, :subject, :email, :name, 'Africa/Douala', 'family_member')""")
                .param("id", id).param("subject", "google-" + id)
                .param("email", name.toLowerCase() + "@example.com").param("name", name)
                .update();
    }
}
