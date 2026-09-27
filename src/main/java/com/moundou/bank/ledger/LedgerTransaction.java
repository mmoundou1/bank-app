package com.moundou.bank.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

/**
 * One row of the {@code transaction} table, as read back.
 *
 * {@code creditor} and {@code debtor} give the direction of this row's own effect
 * (SRS 4.2, CR-14): the creditor gains a claim of {@code amountMinor} on the debtor.
 */
public record LedgerTransaction(
        UUID id,
        TransactionKind kind,
        UUID creditor,
        UUID debtor,
        long amountMinor,
        Currency currency,
        LocalDate transactionDate,
        String note,
        TransactionStatus status,
        String declineReason,
        UUID initiatedBy,
        Instant createdAt,
        Instant decidedAt,
        UUID settlesTransactionId,
        UUID correctsTransactionId,
        boolean openingBalance,
        UUID submissionKey) {
}
