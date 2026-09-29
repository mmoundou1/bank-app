package com.moundou.bank.identity;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.ledger.AuditService;
import com.moundou.bank.ledger.EntryRequest;
import com.moundou.bank.ledger.EntryService;
import com.moundou.bank.ledger.TransactionStatus;
import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slow suite. MB-10 against real Postgres: administration, profiles and the audit view,
 * through the Spring context. Test IDs are from the Test Derivation page (SRS 1.11).
 */
@SpringBootTest(properties = "bank.ledger.onboarding-ends-on=2030-12-31")
class RolesAndProfileIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.migrateFromEmpty();
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired AdminService admin;
    @Autowired ProfileService profiles;
    @Autowired AuditService audit;
    @Autowired EntryService entries;
    @Autowired MemberAdministration administration;

    JdbcClient jdbc;
    LedgerFixtures ledger;
    UUID boss;   // the administrator
    UUID ama;
    UUID ben;

    @BeforeEach
    void freshFamily() {
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        ledger = new LedgerFixtures(jdbc);
        boss = member("Boss", Role.FAMILY_ADMINISTRATOR);
        ama = member("Ama", Role.FAMILY_MEMBER);
        ben = member("Ben", Role.FAMILY_MEMBER);
    }

    UUID member(String name, Role role) {
        UUID id = ledger.member(name);
        String email = name.toLowerCase(Locale.ROOT) + "@example.com";
        jdbc.sql("UPDATE member SET role = :role, email = :email WHERE member_id = :id")
                .param("role", role.dbValue()).param("email", email).param("id", id).update();
        jdbc.sql("INSERT INTO allowed_email (email) VALUES (:e)").param("e", email).update();
        return id;
    }

    boolean active(UUID id) {
        return administration.findById(id).orElseThrow().active();
    }

    boolean listed(String email) {
        return administration.listAllowed().stream().anyMatch(e -> e.email().equals(email));
    }

    // ---- authorization (Auth.Roles-5, T-Roles-5a) -------------------------------------

    @Test
    void aMemberCannotUseAnyAdministrativeOperation() {
        assertThatThrownBy(() -> admin.overview(ama)).isInstanceOf(NotPermittedException.class);
        assertThatThrownBy(() -> admin.allow(ama, "eve@example.com")).isInstanceOf(NotPermittedException.class);
        assertThatThrownBy(() -> admin.disallow(ama, "ben@example.com")).isInstanceOf(NotPermittedException.class);
        assertThatThrownBy(() -> admin.deactivate(ama, ben)).isInstanceOf(NotPermittedException.class);
        assertThat(listed("ben@example.com")).isTrue();
        assertThat(active(ben)).isTrue();
    }

    @Test
    void aDeactivatedAdministratorLosesTheirPowersAtOnce() {
        UUID second = member("Second", Role.FAMILY_ADMINISTRATOR);
        admin.deactivate(boss, second);
        assertThatThrownBy(() -> admin.overview(second)).isInstanceOf(NotPermittedException.class);
    }

    // ---- allow-list (Auth.Roles-8, -9, Data-7) ----------------------------------------

    @Test
    void theAdministratorAddsAndRemovesAddresses() {   // T-Roles-8a
        assertThat(admin.allow(boss, "  New.Cousin@Gmail.com ")).isEqualTo("new.cousin@gmail.com");
        assertThat(listed("new.cousin@gmail.com")).isTrue();
        assertThat(admin.allow(boss, "new.cousin@gmail.com")).as("adding again is harmless").isEqualTo("new.cousin@gmail.com");

        admin.disallow(boss, "new.cousin@gmail.com");
        assertThat(listed("new.cousin@gmail.com")).isFalse();
    }

    @Test
    void aMistypedAddressIsRefused() {
        assertThatThrownBy(() -> admin.allow(boss, "cousin at gmail"))
                .isInstanceOfSatisfying(AdminRuleException.class,
                        e -> assertThat(e.messageKey()).isEqualTo("admin.allowList.invalidEmail"));
    }

    @Test
    void removingAnAddressKeepsTheMemberAndTheirLedger() {   // T-Roles-9a, Data-7
        UUID loan = ledger.approvedLoan(ama, ben, 5_000, LedgerFixtures.USD);
        admin.disallow(boss, "ben@example.com");
        assertThat(administration.findById(ben)).isPresent();
        assertThat(active(ben)).as("removing access is not deactivating").isTrue();
        assertThat(ledger.status(loan)).isEqualTo(TransactionStatus.APPROVED);
    }

    // ---- deactivation (Auth.Roles-3, -7) ----------------------------------------------

    @Test
    void deactivatingKeepsEveryRecordAndEveryBalance() {   // T-Roles-3a, T-Roles-7a
        ledger.approvedLoan(ama, ben, 5_000, LedgerFixtures.USD);
        admin.deactivate(boss, ben);

        assertThat(active(ben)).isFalse();
        assertThat(jdbc.sql("SELECT net_minor FROM member_net WHERE member_id = :b").param("b", ben)
                .query(Long.class).single()).isEqualTo(-5_000L);
        assertThat(listed("ben@example.com")).as("deactivating is not removing access").isTrue();
    }

    @Test
    void aDeactivatedMemberCannotBeChosenAsACounterparty() {   // T-Roles-4a, through MB-11's service
        admin.deactivate(boss, ben);
        var outcome = entries.submit(ama, new EntryRequest(ben, EntryRequest.Role.LENDER, "5", "USD",
                null, null, false, false, UUID.randomUUID()), Locale.ENGLISH);
        assertThat(outcome).isEqualTo(new EntryService.Outcome.Rejected(Map.of("counterparty", "ledger.entry.counterparty.unavailable")));
    }

    // ---- the last administrator (Auth.Roles-12, CR-16) --------------------------------

    @Test
    void theLastAdministratorCannotBeDeactivated() {   // T-Roles-12a
        assertThatThrownBy(() -> admin.deactivate(boss, boss))
                .isInstanceOfSatisfying(AdminRuleException.class,
                        e -> assertThat(e.messageKey()).isEqualTo("admin.lastAdministrator"));
        assertThat(active(boss)).isTrue();
    }

    @Test
    void theLastAdministratorsAddressCannotBeRemoved_butWithASecondBothWork() {   // T-Roles-12b
        assertThatThrownBy(() -> admin.disallow(boss, "boss@example.com")).isInstanceOf(AdminRuleException.class);
        assertThat(listed("boss@example.com")).isTrue();

        UUID second = member("Second", Role.FAMILY_ADMINISTRATOR);
        admin.disallow(boss, "boss@example.com");     // now allowed: Second can still sign in
        assertThat(listed("boss@example.com")).isFalse();
        admin.deactivate(second, boss);               // and Boss, who can no longer sign in, can be deactivated
        assertThat(active(boss)).isFalse();

        assertThatThrownBy(() -> admin.deactivate(second, second))
                .as("Second is now the last one").isInstanceOf(AdminRuleException.class);
    }

    @Test
    void anAdministratorWhoCannotSignInDoesNotCount() {
        UUID second = member("Second", Role.FAMILY_ADMINISTRATOR);
        admin.disallow(boss, "second@example.com");   // Second is active but can no longer sign in
        assertThatThrownBy(() -> admin.deactivate(boss, boss)).isInstanceOf(AdminRuleException.class);
    }

    /**
     * Two administrators deactivate each other at the same moment. Without the lock in
     * lockUsableAdministrators, both would see the other still active and the family would
     * be left with none. With it, exactly one succeeds, every time.
     */
    @Test
    void twoAdministratorsDeactivatingEachOtherAtOnceLeaveOneStanding() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                jdbc.sql("DELETE FROM allowed_email WHERE email LIKE 'x%'").update();
                UUID x = member("X" + round, Role.FAMILY_ADMINISTRATOR);
                jdbc.sql("UPDATE member SET status = 'deactivated' WHERE role = 'family_administrator' AND member_id <> :x")
                        .param("x", x).update();
                UUID y = member("Xy" + round, Role.FAMILY_ADMINISTRATOR);
                CyclicBarrier start = new CyclicBarrier(2);

                Callable<Boolean> xDeactivatesY = () -> attempt(start, () -> admin.deactivate(x, y));
                Callable<Boolean> yDeactivatesX = () -> attempt(start, () -> admin.deactivate(y, x));
                List<Future<Boolean>> results = pool.invokeAll(List.of(xDeactivatesY, yDeactivatesX), 20, TimeUnit.SECONDS);

                long succeeded = results.stream().filter(f -> { try { return f.get(); } catch (Exception e) { throw new AssertionError(e); } }).count();
                assertThat(succeeded).as("round %d", round).isEqualTo(1);
                assertThat(active(x) ^ active(y)).as("exactly one administrator left, round %d", round).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean attempt(CyclicBarrier start, Runnable action) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            action.run();
            return true;
        } catch (AdminRuleException | NotPermittedException refused) {
            return false;   // refused as the last administrator, or already deactivated by the other
        }
    }

    // ---- profile (Auth.Roles-11, CR-16) -----------------------------------------------

    @Test
    void aMembersNewTimeZoneDecidesTheirNextEntrysDate() {   // T-Roles-11a
        profiles.update(ama, "Ama M.", "Pacific/Kiritimati");   // UTC+14: always a day ahead of most places
        assertThat(profiles.view(ama)).isEqualTo(new ProfileRules.Profile("Ama M.", ZoneId.of("Pacific/Kiritimati")));

        var saved = (EntryService.Outcome.Recorded) entries.submit(ama, new EntryRequest(ben, EntryRequest.Role.LENDER,
                "5", "USD", null, null, false, false, UUID.randomUUID()), Locale.ENGLISH);
        assertThat(saved.transaction().transactionDate())
                .isEqualTo(java.time.LocalDate.now(ZoneId.of("Pacific/Kiritimati")));
    }

    @Test
    void anUnknownZoneChangesNothing() {   // T-Roles-11b
        ZoneId before = profiles.view(ama).timeZone();
        assertThat(profiles.update(ama, "Ama", "Mars/Olympus_Mons"))
                .isEqualTo(new ProfileService.Outcome.Rejected(Map.of("timeZone", "profile.timeZone.unknown")));
        assertThat(profiles.view(ama).timeZone()).isEqualTo(before);
    }

    @Test
    void aDeactivatedMemberCannotChangeTheirProfile() {
        admin.deactivate(boss, ben);
        assertThatThrownBy(() -> profiles.update(ben, "Ben", "Europe/Paris")).isInstanceOf(NotPermittedException.class);
    }

    // ---- audit view (Auth.Roles-10, SEC-2) --------------------------------------------

    @Test
    void theAdministratorSeesEverythingBetweenTwoOtherMembers() {   // T-Roles-10a
        ledger.approvedLoan(ama, ben, 10_000, LedgerFixtures.USD);      // Ben owes Ama $100
        ledger.approvedLoan(ben, ama, 2_500, LedgerFixtures.USD);       // Ama owes Ben $25
        ledger.approvedLoan(ben, ama, 3_000, LedgerFixtures.XAF);       // Ama owes Ben 3,000 XAF
        UUID pending = ledger.loan(ama, ben, 999, LedgerFixtures.EUR);  // pending: listed, not counted
        ledger.approvedLoan(ama, boss, 7, LedgerFixtures.USD);          // another pair: not listed

        var view = audit.audit(boss, ama, ben).orElseThrow();

        assertThat(view.transactions()).hasSize(4).anyMatch(t -> t.id().equals(pending));
        assertThat(view.balances()).containsExactly(
                Map.entry(LedgerFixtures.USD, 7_500L), Map.entry(LedgerFixtures.XAF, -3_000L));
        assertThat(audit.audit(boss, ben, ama).orElseThrow().balances())
                .as("from the other side, the signs flip").containsEntry(LedgerFixtures.USD, -7_500L);
    }

    @Test
    void noOneElseCanAuditAPair() {   // T-Roles-10b, SEC-2
        assertThatThrownBy(() -> audit.audit(ama, ben, boss)).isInstanceOf(NotPermittedException.class);
        assertThatThrownBy(() -> audit.audit(ama, ama, ben))
                .as("not even a pair they are part of: that is their own history, MB-14").isInstanceOf(NotPermittedException.class);
    }

    @Test
    void auditingNobodyOrOneselfAgainstOneselfFindsNothing() {
        assertThat(audit.audit(boss, ama, ama)).isEmpty();
        assertThat(audit.audit(boss, ama, UUID.randomUUID())).isEmpty();
    }
}
