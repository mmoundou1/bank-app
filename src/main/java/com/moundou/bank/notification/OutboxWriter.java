package com.moundou.bank.notification;

import java.util.UUID;

/**
 * The one way other modules ask for an alert (Technical Design 2, ADR-010).
 *
 * The caller invokes this inside its own database transaction, so the alert is
 * committed together with the ledger change or not at all: no alert is lost to a crash
 * between commit and send, and a failed send can never undo the ledger change
 * (Notify.Pending-4). Nothing is sent here. The outbox worker (MB-18, Technical
 * Design 7) sends committed rows later, rendering each email at send time (ADR-014).
 */
public interface OutboxWriter {

    enum AlertKind { PENDING_ALERT, SETTLEMENT_ALERT }

    void enqueue(UUID transactionId, UUID recipientId, AlertKind kind);
}
