package com.moundou.bank.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Writes outbox rows to the {@code notification} table (Technical Design D5). State,
 * attempts and timestamps take their defaults: pending, 0, due now.
 */
@Repository
class JdbcOutboxWriter implements OutboxWriter {

    private final JdbcClient jdbc;

    JdbcOutboxWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(UUID transactionId, UUID recipientId, AlertKind kind) {
        jdbc.sql("""
                INSERT INTO notification (notification_id, transaction_id, recipient_id, kind)
                VALUES (:id, :transactionId, :recipientId, :kind)""")
                .param("id", UUID.randomUUID())
                .param("transactionId", transactionId)
                .param("recipientId", recipientId)
                .param("kind", kind.name().toLowerCase())
                .update();
    }
}
