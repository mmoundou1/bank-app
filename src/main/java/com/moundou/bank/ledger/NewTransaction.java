package com.moundou.bank.ledger;

import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * What the application supplies to record a new, pending transaction.
 *
 * Both identifiers come from the application (ADR-015): {@code id} is generated when
 * the row is created, {@code submissionKey} when the entry form is rendered, so a
 * retried POST carries the same key (Technical Design D1, Ledger.Entry-14).
 *
 * This record only carries data. The rules about what may be recorded (validation,
 * the onboarding window, eligibility of a loan for repayment) belong to the ledger
 * services of MB-11, MB-13 and MB-14; the rules that must hold whatever the code does
 * are constraints in the schema.
 */
public record NewTransaction(
        UUID id,
        TransactionKind kind,
        UUID creditor,
        UUID debtor,
        long amountMinor,
        Currency currency,
        LocalDate transactionDate,
        String note,
        UUID initiatedBy,
        UUID settlesTransactionId,
        UUID correctsTransactionId,
        boolean openingBalance,
        UUID submissionKey) {

    public NewTransaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(creditor, "creditor");
        Objects.requireNonNull(debtor, "debtor");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(transactionDate, "transactionDate");
        Objects.requireNonNull(initiatedBy, "initiatedBy");
        Objects.requireNonNull(submissionKey, "submissionKey");
    }
}
