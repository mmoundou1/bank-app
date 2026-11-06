package com.moundou.bank.notification;

import com.moundou.bank.notification.OutboxWriter.AlertKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * All SQL the outbox worker runs on the {@code notification} table (Technical Design 7,
 * D5). It reads and writes this table only: the transaction's status and the recipient's
 * address come from the ledger and identity modules (Technical Design 2).
 *
 * Every method is one statement, committed on its own. None needs {@code @Transactional},
 * and none may be called inside one: the worker sends email between claiming and
 * recording, and no database transaction may be open while it waits on the provider.
 *
 * Specified by NotificationRepositoryIT.
 */
@Repository
class NotificationRepository {

    /**
     * An alert the worker has claimed and must now send, skip or retry.
     *
     * @param attempts sends tried so far, counting the one this claim is for
     */
    record ClaimedAlert(UUID id, UUID transactionId, UUID recipientId, AlertKind kind, int attempts) {
    }

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims up to {@code limit} due alerts, oldest first, and reserves them.
     *
     * Due means still pending, with {@code next_attempt_at} now or earlier. Claiming adds
     * one to {@code attempts} and moves {@code next_attempt_at} to {@code now +
     * reservation}, so the same alert is not claimed again while it is being sent. If the
     * worker records nothing (a crash, a deploy), the reservation runs out and the alert
     * is due again: it may then be sent twice, but it is never lost.
     *
     * {@code SKIP LOCKED} lets two workers claim at the same moment without waiting for
     * each other and without claiming the same row. Rows another worker is claiming are
     * left to it.
     */
    List<ClaimedAlert> claimDue(Instant now, Duration reservation, int limit) {
        return jdbc.sql("""
                        UPDATE notification
                           SET attempts = attempts + 1,
                               next_attempt_at = :reservedUntil
                         WHERE notification_id IN (
                               SELECT notification_id
                                 FROM notification
                                WHERE state = 'pending'
                                  AND next_attempt_at <= :now
                                ORDER BY next_attempt_at, created_at
                                LIMIT :limit
                                  FOR UPDATE SKIP LOCKED)
                        RETURNING notification_id, transaction_id, recipient_id, kind, attempts""")
                .param("now", utc(now))
                .param("reservedUntil", utc(now.plus(reservation)))
                .param("limit", limit)
                .query((rs, row) -> new ClaimedAlert(
                        rs.getObject("notification_id", UUID.class),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getObject("recipient_id", UUID.class),
                        AlertKind.valueOf(rs.getString("kind").toUpperCase()),
                        rs.getInt("attempts")))
                .list();
    }

    /** The email went out. Returns false if the alert was no longer pending. */
    boolean markSent(UUID id, Instant at) {
        return jdbc.sql("""
                        UPDATE notification
                           SET state = 'sent', sent_at = :at, last_error = NULL
                         WHERE notification_id = :id AND state = 'pending'""")
                .param("id", id)
                .param("at", utc(at))
                .update() == 1;
    }

    /**
     * Nothing was sent, because the alert would be out of date (CR-18). Returns false if
     * the alert was no longer pending.
     */
    boolean markSkipped(UUID id) {
        return jdbc.sql("""
                        UPDATE notification
                           SET state = 'skipped'
                         WHERE notification_id = :id AND state = 'pending'""")
                .param("id", id)
                .update() == 1;
    }

    /**
     * The send failed and will be tried again at {@code at}, replacing the reservation.
     * Returns false if the alert was no longer pending.
     */
    boolean retryAt(UUID id, Instant at, String error) {
        return jdbc.sql("""
                        UPDATE notification
                           SET next_attempt_at = :at, last_error = :error
                         WHERE notification_id = :id AND state = 'pending'""")
                .param("id", id)
                .param("at", utc(at))
                .param("error", error)
                .update() == 1;
    }

    /**
     * The last allowed send failed, and the alert will not be tried again. Returns false
     * if the alert was no longer pending.
     */
    boolean markFailed(UUID id, String error) {
        return jdbc.sql("""
                        UPDATE notification
                           SET state = 'failed', last_error = :error
                         WHERE notification_id = :id AND state = 'pending'""")
                .param("id", id)
                .param("error", error)
                .update() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}