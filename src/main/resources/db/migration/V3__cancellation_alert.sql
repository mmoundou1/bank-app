-- CR-18 (SRS 1.14, Notify.Settlement-4; Technical Design D5 and 7).
--
-- cancellation_alert: the counterparty is told when an initiator cancels a pending
--   item. It goes the other way from a settlement_alert, which is for the initiator,
--   so it is its own kind rather than a reuse.
-- skipped: the outbox worker does not send a pending_alert whose item is no longer
--   pending (cancelled, or already decided in the app), and marks the row skipped.
--
-- Both checks only grow, so every existing row stays valid.

ALTER TABLE notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE notification ADD CONSTRAINT notification_kind_check
    CHECK (kind IN ('pending_alert', 'settlement_alert', 'cancellation_alert'));

ALTER TABLE notification DROP CONSTRAINT notification_state_check;
ALTER TABLE notification ADD CONSTRAINT notification_state_check
    CHECK (state IN ('pending', 'sent', 'failed', 'skipped'));
