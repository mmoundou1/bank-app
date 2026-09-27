package com.moundou.bank.ledger;

/**
 * The kinds of ledger record in Release 1.0 (SRS 4.2). Mirrors the
 * {@code transaction_kind_check} constraint in V1__ledger_schema.sql.
 * Release 2.0 adds FORGIVENESS here and in a new migration.
 */
public enum TransactionKind {
    LOAN, REPAYMENT, COMPENSATING;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static TransactionKind fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
