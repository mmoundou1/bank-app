package com.moundou.bank.ledger;

/**
 * The derived lifecycle of an approved loan (Technical Design 5). Computed by
 * {@link TransactionRepository#loanLifecycle}, never stored.
 *
 * <pre>
 *   Outstanding --L2 repayment approved--------------> Settled
 *   Outstanding --L3 correction of loan approved-----> Reversed
 *   Settled     --L4 correction of repayment approved-> Outstanding
 *   Reversed    --L5 correction of correction approved-> Outstanding
 * </pre>
 */
public enum LoanLifecycle {
    /** Approved, not settled by an unreversed repayment, not reversed (SRS glossary). */
    OUTSTANDING,
    /** Has an approved repayment that has not itself been reversed. */
    SETTLED,
    /** Reversed by an approved compensating entry that has not itself been reversed. */
    REVERSED,
    /** Not an approved loan at all: pending, declined, cancelled, or another kind. */
    NOT_AN_APPROVED_LOAN
}
