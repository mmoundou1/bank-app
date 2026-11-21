package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static com.moundou.bank.support.LedgerFixtures.DATE;
import static com.moundou.bank.support.LedgerFixtures.EUR;
import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.XAF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Slow suite. What Home and People get from the dashboard service (MB-15, MB-17), through
 * the real Spring context against Postgres: Model 6's report tables RT-1 to RT-4 and
 * DT-3, and SRS 1.16 Dashboard.Net-1 to -4 and Dashboard.Counterparty-1 to -3. What each
 * query returns is DashboardQueriesIT's; degraded mode (DT-1) is DashboardServiceTests'.
 *
 * Written first, as the specification (Technical Design 14.6). It fixes these names:
 * <pre>
 *   @Service DashboardService: HomeView home(UUID), PeopleView people(UUID),
 *                              PersonView person(UUID member, UUID person)
 *   Summary(List&lt;CurrencyPosition&gt; positions)
 *   CurrencyPosition(Currency currency, long netMinor, List&lt;ActiveLoan&gt; loans)
 *   ActiveLoan(UUID loanId, boolean madeByYou, UUID otherMember, long amountMinor,
 *              LocalDate date, boolean openingBalance, boolean repayable)
 *   PersonBalance(UUID person, boolean active, Currency currency, long netMinor)
 *   PersonDetail(UUID person, boolean active, List&lt;PersonCurrency&gt; currencies)
 *   PersonCurrency(Currency currency, long netMinor, List&lt;RecordLine&gt; records)
 *   RecordLine(LedgerTransaction transaction, long effectMinor)
 *   sealed HomeView { Live(Summary summary), Offline(Optional&lt;Summary&gt; cached) }
 *   sealed PeopleView { Live(List&lt;PersonBalance&gt; balances), Offline() }
 *   sealed PersonView { Live(PersonDetail detail), Offline() }
 * </pre>
 * Every amount is from the member's side: positive means owed to the member.
 */
@SpringBootTest
class DashboardServiceIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.migrateFromEmpty();
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired DashboardService dashboard;
    @Autowired DataSource dataSource;

    JdbcClient jdbc;
    LedgerFixtures ledger;
    UUID ama;
    UUID ben;
    UUID cleo;
    UUID dede;

    @BeforeEach
    void freshLedger() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        ama = ledger.member("Ama");
        ben = ledger.member("Ben");
        cleo = ledger.member("Cleo");
        dede = ledger.member("Dédé");
    }

    private Summary summaryOf(UUID member) {
        return switch (dashboard.home(member)) {
            case HomeView.Live live -> live.summary();
            case HomeView.Offline offline -> throw new AssertionError("the database is up: " + offline);
        };
    }

    private List<PersonBalance> balancesOf(UUID member) {
        return switch (dashboard.people(member)) {
            case PeopleView.Live live -> live.balances();
            case PeopleView.Offline offline -> throw new AssertionError("the database is up");
        };
    }

    private PersonDetail detail(UUID member, UUID person) {
        return switch (dashboard.person(member, person)) {
            case PersonView.Live live -> live.detail();
            case PersonView.Offline offline -> throw new AssertionError("the database is up");
        };
    }

    @Nested
    class WhereYouStand {

        @Test
        void oneRowPerCurrencyWithTheNetFromYourSide() {                        // T-Net-1a, RT-1
            ledger.approvedLoan(ama, ben, 10_000, USD);
            ledger.approvedLoan(ama, cleo, 5_000, USD);
            ledger.approvedLoan(ben, ama, 3_000, USD);

            assertThat(summaryOf(ama).positions())
                    .extracting(CurrencyPosition::currency, CurrencyPosition::netMinor)
                    .containsExactly(tuple(USD, 12_000L));
            assertThat(summaryOf(ben).positions())
                    .extracting(CurrencyPosition::currency, CurrencyPosition::netMinor)
                    .containsExactly(tuple(USD, -7_000L));
        }

        @Test
        void noApprovedTransactionMeansAnEmptySummary() {                       // T-Net-1b, Model 6 Q1
            ledger.loan(ama, ben, 2_000, USD);

            assertThat(summaryOf(ama).positions()).isEmpty();
        }

        @Test
        void currenciesComeInTheFixedOrderAndAreNeverAddedTogether() {          // T-Net-1c, Model 6 Q2
            ledger.approvedLoan(ama, ben, 50_000, XAF);
            ledger.approvedLoan(ben, ama, 700, EUR);
            ledger.approvedLoan(ama, ben, 2_000, USD);

            assertThat(summaryOf(ama).positions())
                    .extracting(CurrencyPosition::currency, CurrencyPosition::netMinor)
                    .containsExactly(tuple(USD, 2_000L), tuple(EUR, -700L), tuple(XAF, 50_000L));
        }

        @Test
        void aCurrencyBackAtZeroKeepsItsRow() {                                 // Model 6 Q2, DT-2 "All square"
            UUID lent = ledger.approvedLoan(ama, ben, 2_000, EUR);
            ledger.approve(ledger.repayment(lent));

            assertThat(summaryOf(ama).positions())
                    .extracting(CurrencyPosition::currency, CurrencyPosition::netMinor, p -> p.loans().size())
                    .containsExactly(tuple(EUR, 0L, 0));
        }
    }

    @Nested
    class ActiveLoans {

        @Test
        void eachCurrencyListsItsOutstandingLoansMadeFirstThenReceived() {     // T-Net-3a, -3b, RT-2
            UUID received = ledger.approvedLoan(cleo, ama, 2_000, USD);
            UUID madeFirst = ledger.approvedLoan(ama, ben, 1_000, USD);
            UUID madeSecond = ledger.approvedLoan(ama, cleo, 3_000, USD);
            UUID settled = ledger.approvedLoan(ama, ben, 4_000, USD);
            ledger.approve(ledger.repayment(settled));
            UUID reversed = ledger.approvedLoan(ben, ama, 5_000, USD);
            ledger.approve(ledger.correction(reversed));
            ledger.loan(ama, ben, 6_000, USD);
            UUID inXaf = ledger.approvedLoan(ben, ama, 9_000, XAF);

            List<CurrencyPosition> positions = summaryOf(ama).positions();

            assertThat(positions.get(0).currency()).isEqualTo(USD);
            assertThat(positions.get(0).loans())
                    .extracting(ActiveLoan::loanId, ActiveLoan::madeByYou, ActiveLoan::otherMember, ActiveLoan::amountMinor)
                    .containsExactly(
                            tuple(madeFirst, true, ben, 1_000L),
                            tuple(madeSecond, true, cleo, 3_000L),
                            tuple(received, false, cleo, 2_000L));
            assertThat(positions.get(1).currency()).isEqualTo(XAF);
            assertThat(positions.get(1).loans()).extracting(ActiveLoan::loanId).containsExactly(inXaf);
        }

        @Test
        void activeLoansAddUpToTheFigureAboveThem() {                           // T-Net-3b, RT-2 check
            ledger.approvedLoan(ama, ben, 10_000, USD);
            ledger.approvedLoan(ben, ama, 2_500, USD);
            UUID again = ledger.approvedLoan(cleo, ama, 40_000, XAF);
            ledger.approve(ledger.correction(ledger.approve(ledger.repayment(again))));

            for (CurrencyPosition p : summaryOf(ama).positions()) {
                long made = p.loans().stream().filter(ActiveLoan::madeByYou).mapToLong(ActiveLoan::amountMinor).sum();
                long received = p.loans().stream().filter(l -> !l.madeByYou()).mapToLong(ActiveLoan::amountMinor).sum();
                assertThat(made - received).as("%s", p.currency()).isEqualTo(p.netMinor());
            }
        }

        @Test
        void onlyALoanYouOweWithNoRepaymentPendingCanBeRepaid() {              // Model 6 Q9, Ledger.Repayment-8
            UUID owed = ledger.approvedLoan(ben, ama, 1_000, USD);
            UUID owedBeingRepaid = ledger.approvedLoan(cleo, ama, 2_000, USD);
            ledger.repayment(owedBeingRepaid);
            UUID made = ledger.approvedLoan(ama, ben, 3_000, USD);

            assertThat(summaryOf(ama).positions().get(0).loans())
                    .extracting(ActiveLoan::loanId, ActiveLoan::repayable)
                    .containsExactlyInAnyOrder(tuple(owed, true), tuple(owedBeingRepaid, false), tuple(made, false));
        }

        @Test
        void anOpeningBalanceSaysSoAndKeepsItsAgreedDate() {                   // Ledger.Entry-15, RT-2
            UUID opening = ledger.approve(ledger.insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN,
                    ama, ben, 25_000, XAF, DATE.minusYears(4), null, ama, null, null, true, UUID.randomUUID())));

            assertThat(summaryOf(ama).positions().get(0).loans())
                    .extracting(ActiveLoan::loanId, ActiveLoan::openingBalance, ActiveLoan::date)
                    .containsExactly(tuple(opening, true, DATE.minusYears(4)));
        }
    }

    @Nested
    class People {

        @Test
        void oneRowPerPersonAndCurrencySortedByNameFromYourSide() {             // T-Cpty-1a, RT-3
            ledger.approvedLoan(cleo, ama, 3_000, USD);
            ledger.approvedLoan(ama, ben, 2_000, XAF);
            ledger.approvedLoan(ama, ben, 500, USD);

            assertThat(balancesOf(ama))
                    .extracting(PersonBalance::person, PersonBalance::currency, PersonBalance::netMinor)
                    .containsExactly(tuple(ben, USD, 500L), tuple(ben, XAF, 2_000L), tuple(cleo, USD, -3_000L));
            assertThat(balancesOf(ben))
                    .extracting(PersonBalance::person, PersonBalance::currency, PersonBalance::netMinor)
                    .containsExactly(tuple(ama, USD, -500L), tuple(ama, XAF, -2_000L));
        }

        @Test
        void anActiveMemberYouAreEvenWithIsStillListed() {                      // T-Cpty-3a, DT-3 R3
            UUID lent = ledger.approvedLoan(ama, ben, 2_000, USD);
            ledger.approve(ledger.repayment(lent));

            assertThat(balancesOf(ama))
                    .extracting(PersonBalance::person, PersonBalance::active, PersonBalance::netMinor)
                    .containsExactly(tuple(ben, true, 0L));
        }

        @Test
        void aDeactivatedMemberIsListedOnlyWhileTheBalanceIsNotZero() {        // T-Cpty-1b, DT-3 R4, R5
            ledger.approvedLoan(ama, ben, 2_000, USD);                          // Ben: still owes
            UUID lent = ledger.approvedLoan(ama, cleo, 1_000, USD);
            ledger.approve(ledger.repayment(lent));                             // Cleo: even
            ledger.deactivate(ben);
            ledger.deactivate(cleo);                                            // Dédé: never dealt with

            assertThat(balancesOf(ama))
                    .extracting(PersonBalance::person, PersonBalance::active, PersonBalance::netMinor)
                    .containsExactly(tuple(ben, false, 2_000L));
        }

        @Test
        void theRowsAddUpToWhereYouStand() {                                    // T-Cpty-1b, RT-3 check
            ledger.approvedLoan(ama, ben, 2_000, USD);
            ledger.approvedLoan(cleo, ama, 3_500, USD);
            ledger.approvedLoan(ama, dede, 100, USD);
            ledger.deactivate(dede);

            long fromPeople = balancesOf(ama).stream().mapToLong(PersonBalance::netMinor).sum();

            assertThat(fromPeople).isEqualTo(summaryOf(ama).positions().get(0).netMinor()).isEqualTo(-1_400L);
        }

        @Test
        void noApprovedTransactionMeansNoRows() {                               // Model 6 Q5
            ledger.loan(ama, ben, 2_000, USD);

            assertThat(balancesOf(ama)).isEmpty();
        }
    }

    @Nested
    class OnePerson {

        @Test
        void theRecordsWithThatPersonAddUpToTheBalance() {                     // T-Cpty-2a, RT-4
            UUID first = ledger.approvedLoan(ama, ben, 2_000, USD);
            UUID borrowed = ledger.approvedLoan(ben, ama, 700, USD);
            UUID repaid = ledger.approve(ledger.repayment(borrowed));
            UUID inXaf = ledger.approvedLoan(ben, ama, 15_000, XAF);
            ledger.loan(ama, ben, 99, USD);                                     // pending: not a record of the balance
            ledger.approvedLoan(ama, cleo, 300, USD);                           // someone else

            PersonDetail detail = detail(ama, ben);

            assertThat(detail.person()).isEqualTo(ben);
            assertThat(detail.active()).isTrue();
            assertThat(detail.currencies()).extracting(PersonCurrency::currency, PersonCurrency::netMinor)
                    .containsExactly(tuple(USD, 2_000L), tuple(XAF, -15_000L));
            PersonCurrency usd = detail.currencies().get(0);
            assertThat(usd.records()).extracting(r -> r.transaction().id(), RecordLine::effectMinor)
                    .containsExactlyInAnyOrder(tuple(first, 2_000L), tuple(borrowed, -700L), tuple(repaid, 700L));
            assertThat(usd.records().stream().mapToLong(RecordLine::effectMinor).sum()).isEqualTo(usd.netMinor());
            assertThat(detail.currencies().get(1).records()).extracting(r -> r.transaction().id()).containsExactly(inXaf);
        }

        @Test
        void theSameRecordsReadFromTheOtherSide() {                             // RT-4, from both sides
            ledger.approvedLoan(ama, ben, 2_000, USD);

            assertThat(detail(ben, ama).currencies())
                    .extracting(PersonCurrency::netMinor, c -> c.records().get(0).effectMinor())
                    .containsExactly(tuple(-2_000L, -2_000L));
        }

        @Test
        void newestFirst() {                                                    // RT-4 sort
            UUID older = ledger.approvedLoan(ama, ben, 100, USD);
            UUID newer = ledger.approvedLoan(ama, ben, 200, USD);

            assertThat(detail(ama, ben).currencies().get(0).records())
                    .extracting(r -> r.transaction().id()).containsExactly(newer, older);
        }

        @Test
        void aDeactivatedPersonCanStillBeOpened() {                             // Model 6 DAR 3.3, Auth.Roles-7
            ledger.approvedLoan(ama, ben, 2_000, USD);
            ledger.deactivate(ben);

            assertThat(detail(ama, ben).active()).isFalse();
        }

        @Test
        void someoneYouHaveNoApprovedRecordWithIsRefusedAlike() {               // SEC-2, Model 6 DAR 3.3
            ledger.loan(ama, ben, 2_000, USD);                                  // pending only
            ledger.approvedLoan(cleo, dede, 500, USD);                          // a pair Ama is not part of

            for (UUID notYours : List.of(ben, cleo, dede, ama, UUID.randomUUID())) {
                assertThatThrownBy(() -> dashboard.person(ama, notYours))
                        .as("person %s", notYours)
                        .isInstanceOf(NotPermittedException.class);
            }
        }
    }

    @Test
    void viewingChangesNothing() {                                              // T-Net-4a, Dashboard.Net-4
        UUID lent = ledger.approvedLoan(ama, ben, 2_000, USD);
        ledger.repayment(lent);
        ledger.approvedLoan(cleo, ama, 300, XAF);
        String before = snapshot();

        dashboard.home(ama);
        dashboard.people(ama);
        dashboard.person(ama, ben);
        dashboard.home(ben);

        assertThat(snapshot()).isEqualTo(before);
    }

    private String snapshot() {
        return jdbc.sql("""
                SELECT string_agg(t::text, '|' ORDER BY t.transaction_id) FROM transaction t""").query(String.class).single()
                + jdbc.sql("SELECT count(*) FROM transaction_status_history").query(Long.class).single()
                + jdbc.sql("SELECT count(*) FROM notification").query(Long.class).single()
                + jdbc.sql("SELECT string_agg(m::text, '|' ORDER BY m.member_id) FROM member m").query(String.class).single();
    }
}