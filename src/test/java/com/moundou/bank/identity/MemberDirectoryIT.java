package com.moundou.bank.identity;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The counterparty list on the entry form (Ledger.Entry-1, -3; Auth.Roles-4),
 * against real Postgres. No Spring context: the query is all there is to test.
 */
class MemberDirectoryIT {

    JdbcClient jdbc;
    LedgerFixtures fixtures;
    MemberDirectory directory;

    @BeforeAll
    static void schema() {
        TestDatabase.migrateFromEmpty();
    }

    @BeforeEach
    void emptyFamily() {
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        fixtures = new LedgerFixtures(jdbc);
        directory = new MemberDirectory(jdbc);
    }

    @Test
    void offersEveryActiveMemberExceptYourselfInNameOrder() {
        UUID me = fixtures.member("Matthieu");
        fixtures.member("Chloe");
        fixtures.member("ben");                          // lower case: sorts after Chloe if case counted
        UUID gone = fixtures.member("Ama");
        jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :id").param("id", gone).update();

        assertThat(directory.counterpartiesFor(me))
                .extracting(Member::displayName)
                .containsExactly("ben", "Chloe");        // not me, not Ama; case ignored in the order
    }

    @Test
    void aMemberAloneInTheFamilyHasNobodyToChoose() {
        UUID me = fixtures.member("Matthieu");
        assertThat(directory.counterpartiesFor(me)).isEmpty();
    }
}
