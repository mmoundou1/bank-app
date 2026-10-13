package com.moundou.bank.notification;

import com.moundou.bank.support.LedgerFixtures;
import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static com.moundou.bank.support.LedgerFixtures.USD;
import static com.moundou.bank.support.LedgerFixtures.assertRejectedBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The outbox table accepts every alert kind the code can write, and the
 * states the worker uses (Technical Design D5; V1, and V3 for CR-18). The writer
 * stores {@link OutboxWriter.AlertKind} by name, so a kind added to the enum without
 * a migration fails here rather than in a user's cancel.
 */
class OutboxSchemaIT {

    private static JdbcClient jdbc;
    private JdbcOutboxWriter outbox;
    private UUID item;
    private UUID ben;

    @BeforeAll
    static void migrate() {
        TestDatabase.migrateFromEmpty();
        jdbc = JdbcClient.create(TestDatabase.dataSource());
    }

    @BeforeEach
    void oneItem() {
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member").update();
        LedgerFixtures ledger = new LedgerFixtures(jdbc);
        UUID ama = ledger.member("Ama");
        ben = ledger.member("Ben");
        item = ledger.loan(ama, ben, 2000, USD);
        outbox = new JdbcOutboxWriter(jdbc);
    }

    @Test
    void everyAlertKindCanBeWritten() {
        for (OutboxWriter.AlertKind kind : OutboxWriter.AlertKind.values()) {
            outbox.enqueue(item, ben, kind);
        }
        assertThat(jdbc.sql("SELECT kind FROM notification ORDER BY kind").query(String.class).list())
                .containsExactly("cancellation_alert", "pending_alert", "settlement_alert");
    }

    @Test
    void aPendingAlertCanBeMarkedSkipped() {                                     // T-NSettle-4b, CR-18
        outbox.enqueue(item, ben, OutboxWriter.AlertKind.PENDING_ALERT);

        assertThat(jdbc.sql("UPDATE notification SET state = 'skipped'").update()).isOne();
    }

    @Test
    void anUnknownKindOrStateIsRefused() {
        assertRejectedBy("notification_kind_check", () -> jdbc.sql("""
                INSERT INTO notification (notification_id, transaction_id, recipient_id, kind)
                VALUES (:id, :item, :ben, 'reminder')""")
                .param("id", UUID.randomUUID()).param("item", item).param("ben", ben).update());

        outbox.enqueue(item, ben, OutboxWriter.AlertKind.PENDING_ALERT);
        assertRejectedBy("notification_state_check",
                () -> jdbc.sql("UPDATE notification SET state = 'lost'").update());
    }
}
