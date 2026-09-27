package com.moundou.bank.ledger;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.MutableClock;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slow suite. Transaction entry through the real Spring context, against Postgres
 * with the migrations applied: the service, its transaction, the schema and the outbox
 * together. Test IDs are from the Test Derivation page.
 */
@SpringBootTest(properties = "bank.ledger.onboarding-ends-on=2026-10-31")
class EntryServiceIT {

    /** 2026-09-27 16:00 UTC: the 27th in Douala, where the fixture members live. */
    static final Instant NOW = Instant.parse("2026-09-27T16:00:00Z");

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

    @Autowired EntryService entries;
    @Autowired MutableClock clock;

    JdbcClient jdbc;
    LedgerFixtures ledger;
    UUID ama;
    UUID ben;

    @BeforeEach
    void freshLedger() {
        clock.set(NOW);
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        ama = ledger.member("Ama");
        ben = ledger.member("Ben");
    }

    // ---- helpers ------------------------------------------------------------------

    EntryRequest lend(UUID to, String amount, String currency) {
        return new EntryRequest(to, EntryRequest.Role.LENDER, amount, currency, null, null, false, false, UUID.randomUUID());
    }

    LedgerTransaction recorded(EntryService.Outcome outcome) {
        assertThat(outcome).isInstanceOf(EntryService.Outcome.Recorded.class);
        return ((EntryService.Outcome.Recorded) outcome).transaction();
    }

    int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    Map<String, Long> netPositions() {
        return jdbc.sql("SELECT member_id::text || ' ' || currency, net_minor FROM member_net")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ---- tests --------------------------------------------------------------------

    @Test
    void aValidEntryIsSavedPendingWithItsInitiatorAndTime() {   // T-Entry-4a, -9a
        LedgerTransaction t = recorded(entries.submit(ama, lend(ben, "20.00", "USD"), Locale.ENGLISH));

        assertThat(t.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(t.creditor()).isEqualTo(ama);
        assertThat(t.debtor()).isEqualTo(ben);
        assertThat(t.amountMinor()).isEqualTo(2000);
        assertThat(t.initiatedBy()).isEqualTo(ama);
        assertThat(t.createdAt()).isNotNull();
        assertThat(t.decidedAt()).isNull();
        assertThat(t.transactionDate()).isEqualTo(LocalDate.of(2026, 9, 27));   // today in Douala
    }

    @Test
    void savingAnEntryChangesNoBalance() {   // T-Entry-5a
        ledger.approvedLoan(ben, ama, 5_000, LedgerFixtures.USD);
        Map<String, Long> before = netPositions();

        recorded(entries.submit(ama, lend(ben, "75.00", "USD"), Locale.ENGLISH));

        assertThat(netPositions()).isEqualTo(before);
    }

    @Test
    void oneAlertIsQueuedForTheCounterpartyOnly_andTheCreationIsLogged() {   // T-Entry-6a, Data-5
        LedgerTransaction t = recorded(entries.submit(ama, lend(ben, "1.00", "USD"), Locale.ENGLISH));

        var alerts = jdbc.sql("SELECT recipient_id, kind, state FROM notification WHERE transaction_id = :t")
                .param("t", t.id())
                .query((rs, i) -> List.of(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getString(3)))
                .list();
        assertThat(alerts).containsExactly(List.of(ben.toString(), "pending_alert", "pending"));

        var history = jdbc.sql("SELECT from_status, to_status, actor_id FROM transaction_status_history WHERE transaction_id = :t")
                .param("t", t.id())
                .query((rs, i) -> java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)))
                .list();
        assertThat(history).containsExactly(java.util.Arrays.asList(null, "pending", ama));
    }

    @Test
    void aBorrowerEntryAlertsTheLender() {
        EntryRequest borrow = new EntryRequest(ben, EntryRequest.Role.BORROWER, "5", "EUR", null, null, false, false, UUID.randomUUID());
        LedgerTransaction t = recorded(entries.submit(ama, borrow, Locale.ENGLISH));
        assertThat(t.creditor()).isEqualTo(ben);
        assertThat(jdbc.sql("SELECT recipient_id FROM notification WHERE transaction_id = :t")
                .param("t", t.id()).query(UUID.class).single()).isEqualTo(ben);
    }

    @Test
    void aSplitIsStoredAsAnOrdinaryLoanForHalf() {   // T-Entry-7a, -7b
        EntryRequest split = new EntryRequest(ben, null, "40.00", "USD", null, "dinner", true, false, UUID.randomUUID());
        LedgerTransaction t = recorded(entries.submit(ama, split, Locale.ENGLISH));
        assertThat(t.kind()).isEqualTo(TransactionKind.LOAN);
        assertThat(t.amountMinor()).isEqualTo(2000);
        assertThat(t.creditor()).isEqualTo(ama);
    }

    @Test
    void oddUnitSplitsNeverBreakTheZeroSum() {   // T-Entry-11b, INT-7
        for (int i = 0; i < 10; i++) {
            EntryRequest split = new EntryRequest(i % 2 == 0 ? ben : ama, null, "25.0" + (2 * i % 10 + 1), "USD",
                    null, null, true, false, UUID.randomUUID());
            UUID payer = i % 2 == 0 ? ama : ben;
            ledger.approve(recorded(entries.submit(payer, split, Locale.ENGLISH)).id());
        }
        assertThat(netPositions().values().stream().mapToLong(Long::longValue).sum()).isZero();
    }

    @Test
    void aDateReadsBackAsTheSameCalendarDayWhateverTheServersZone() {   // T-Entry-12c
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Bogota"));   // UTC-5, no daylight saving
            EntryRequest r = new EntryRequest(ben, EntryRequest.Role.LENDER, "3", "USD",
                    LocalDate.of(2026, 9, 1), null, false, false, UUID.randomUUID());
            UUID id = recorded(entries.submit(ama, r, Locale.ENGLISH)).id();
            assertThat(ledger.repository().findById(id).orElseThrow().transactionDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(jdbc.sql("SELECT transaction_date::text FROM transaction WHERE transaction_id = :id")
                    .param("id", id).query(String.class).single()).isEqualTo("2026-09-01");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void francsAreStoredAsWholeFrancs() {   // T-Entry-13a
        LedgerTransaction t = recorded(entries.submit(ama, lend(ben, "10,000", "XAF"), Locale.ENGLISH));
        assertThat(t.currency().getCurrencyCode()).isEqualTo("XAF");
        assertThat(t.amountMinor()).isEqualTo(10_000);
    }

    @Test
    void aRetriedSubmissionIsRecordedOnce() {   // T-Entry-14a
        EntryRequest once = lend(ben, "12.00", "USD");
        LedgerTransaction first = recorded(entries.submit(ama, once, Locale.ENGLISH));
        for (int retry = 0; retry < 3; retry++) {
            var outcome = (EntryService.Outcome.Recorded) entries.submit(ama, once, Locale.ENGLISH);
            assertThat(outcome.alreadyRecorded()).isTrue();
            assertThat(outcome.transaction().id()).isEqualTo(first.id());
        }
        assertThat(count("transaction")).isOne();
        assertThat(count("transaction_status_history")).isOne();
        assertThat(count("notification")).as("the counterparty is alerted once").isOne();
    }

    @Test
    void retriesArrivingTogetherAreStillRecordedOnce() throws Exception {   // T-Entry-14a, concurrent
        EntryRequest once = lend(ben, "12.00", "USD");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<UUID>> attempts = IntStream.range(0, 4)
                    .<Callable<UUID>>mapToObj(i -> () -> recorded(entries.submit(ama, once, Locale.ENGLISH)).id())
                    .toList();
            List<UUID> ids = pool.invokeAll(attempts).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }).distinct().toList();
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("transaction")).isOne();
        assertThat(count("notification")).isOne();
    }

    @Test
    void anOpeningBalanceIsAcceptedDuringOnboarding() {   // T-Entry-15a
        EntryRequest opening = new EntryRequest(ben, EntryRequest.Role.LENDER, "150.00", "USD",
                LocalDate.of(2022, 3, 14), "before the app", false, true, UUID.randomUUID());
        LedgerTransaction t = recorded(entries.submit(ama, opening, Locale.ENGLISH));
        assertThat(t.openingBalance()).isTrue();
        assertThat(t.kind()).isEqualTo(TransactionKind.LOAN);
        assertThat(t.transactionDate()).isEqualTo(LocalDate.of(2022, 3, 14));
        assertThat(t.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(count("notification")).isOne();
    }

    @Test
    void anOpeningBalanceAfterOnboardingIsRejectedAndNothingIsWritten() {   // T-Entry-16a
        clock.set(Instant.parse("2026-11-01T12:00:00Z"));
        EntryRequest opening = new EntryRequest(ben, EntryRequest.Role.LENDER, "150.00", "USD",
                LocalDate.of(2022, 3, 14), null, false, true, UUID.randomUUID());
        assertThat(entries.submit(ama, opening, Locale.ENGLISH)).isEqualTo(new EntryService.Outcome.Rejected(
                Map.of("openingBalance", "ledger.entry.openingBalance.closed")));
        assertThat(count("transaction") + count("notification") + count("transaction_status_history")).isZero();
    }

    @Test
    void anInvalidEntryWritesNothing() {   // T-Entry-2a, -3a
        var outcome = entries.submit(ama, new EntryRequest(ama, EntryRequest.Role.LENDER, "0", "USD",
                null, null, false, false, UUID.randomUUID()), Locale.ENGLISH);
        assertThat(outcome).isInstanceOf(EntryService.Outcome.Rejected.class);
        assertThat(((EntryService.Outcome.Rejected) outcome).fieldErrors()).containsOnlyKeys("counterparty", "amount");
        assertThat(count("transaction")).isZero();
    }

    @Test
    void aDeactivatedMemberCannotRecordAnything() {   // Auth.Login-5
        jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :m").param("m", ama).update();
        assertThatThrownBy(() -> entries.submit(ama, lend(ben, "1", "USD"), Locale.ENGLISH))
                .isInstanceOf(NotPermittedException.class);
        assertThat(count("transaction")).isZero();
    }

    @Test
    void aDeactivatedCounterpartyCannotBeChosen() {   // Auth.Roles-4
        jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :m").param("m", ben).update();
        assertThat(entries.submit(ama, lend(ben, "1", "USD"), Locale.ENGLISH)).isEqualTo(new EntryService.Outcome.Rejected(
                Map.of("counterparty", "ledger.entry.counterparty.unavailable")));
    }
}
