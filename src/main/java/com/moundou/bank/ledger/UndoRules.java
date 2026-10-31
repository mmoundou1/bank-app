package com.moundou.bank.ledger;

import java.util.List;

/**
 * Whether a record can be undone by a compensating entry, and if not, why
 * (Ledger.History-9, -11; INT-6). Pure code: no database, no actor. The answer is a
 * fact about the record, the same for both of its members.
 *
 * Used twice, so the history's "Undo this record" link and the submit can never
 * disagree: the history asks for every row, and the submit asks again after locking
 * the target.
 *
 * Specified by UndoRulesTests.
 */
public final class UndoRules {

    private UndoRules() {
    }

    /**
     * The answer: undoable, or refused with the message key that says why.
     */
    public sealed interface Check {
        record Undoable() implements Check {
        }

        record Refused(String messageKey) implements Check {
        }
    }

    /**
     * @param target  the record to undo
     * @param related rows that may point at it: its corrections, and for a loan its
     *                repayments and their corrections. Rows that point elsewhere are
     *                ignored, so the history can pass its whole list.
     */
    public static Check check(LedgerTransaction target, List<LedgerTransaction> related) {
        // Question 1 (yours). CHANGED: the real message key instead of "TBD".
        if (target.kind() == TransactionKind.COMPENSATING)
            return new Check.Refused("ledger.history.undo.isCorrection");

        // Question 2 (yours). CHANGED: the real message key instead of "TBD".
        if (target.status() != TransactionStatus.APPROVED)
            return new Check.Refused("ledger.history.undo.notApproved");

        // Question 3. ADDED: it was missing. Is the target itself already undone?
        if (isUndone(target, related))
            return new Check.Refused("ledger.history.correction.alreadyCorrected");

        // Question 4. CHANGED, three ways:
        //  - only a loan can be settled, so only a loan is asked;
        //  - only repayments of THIS loan count (settlesTransactionId points at it);
        //  - EVERY such repayment is looked at, not only the first, and the loan is
        //    refused when one of them is approved and NOT undone. Yours refused when
        //    the repayment WAS undone, the opposite.
        if (target.kind() == TransactionKind.LOAN) {
            boolean settled = related.stream()
                    .anyMatch(tx -> tx.kind() == TransactionKind.REPAYMENT
                            && target.id().equals(tx.settlesTransactionId())
                            && tx.status() == TransactionStatus.APPROVED
                            && !isUndone(tx, related));
            if (settled)
                return new Check.Refused("ledger.history.correction.conflictsWithRepayment");
        }

        return new Check.Undoable();
    }

    /**
     * ADDED. Whether an approved correction points at {@code row}. Used for the target
     * (question 3) and for each of a loan's repayments (question 4): one rule, two rows.
     * A correction points with correctsTransactionId; settlesTransactionId is only ever
     * set on a repayment.
     */
    private static boolean isUndone(LedgerTransaction row, List<LedgerTransaction> related) {
        return related.stream()
                .anyMatch(tx -> tx.kind() == TransactionKind.COMPENSATING
                        && tx.status() == TransactionStatus.APPROVED
                        && row.id().equals(tx.correctsTransactionId()));
    }

}
