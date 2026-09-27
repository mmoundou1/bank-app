package com.moundou.bank.ledger;

/**
 * Stored lifecycle state of a transaction (SRS 4.1, Model 5). Mirrors the
 * {@code transaction_status_check} constraint.
 *
 * Which transitions are legal, and for whom, is not decided here: that is the
 * TransactionStateMachine of Technical Design 5, built in MB-12.
 */
public enum TransactionStatus {
    PENDING, APPROVED, DECLINED, CANCELLED;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static TransactionStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
