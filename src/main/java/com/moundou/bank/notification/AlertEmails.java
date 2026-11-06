package com.moundou.bank.notification;

import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.notification.NotificationRepository.ClaimedAlert;

/**
 * Writes the email for one alert: the recipient's address, the subject and the body.
 *
 * Rendered at send time from the message bundle, in the recipient's locale, so the
 * notification row stores no text (ADR-014, Technical Design D5). The implementation
 * looks up what it needs about the members itself, through the identity module, so the
 * outbox worker never touches member data (Technical Design 2).
 *
 * An interface so the worker's tests can use a small stand-in. The real one comes with
 * the email templates.
 */
interface AlertEmails {

    /**
     * @param alert       the claimed alert: its kind and recipient
     * @param transaction its transaction, as read when the alert is handled
     */
    EmailMessage compose(ClaimedAlert alert, LedgerTransaction transaction);
}