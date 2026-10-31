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
 * SKELETON: the signatures are fixed by UndoRulesTests; the body is yours to write.
 */
public final class UndoRules {

    private UndoRules() {
    }

    /** The answer: undoable, or refused with the message key that says why. */
    public sealed interface Check {
        record Undoable() implements Check { }
        record Refused(String messageKey) implements Check { }
    }

    /**
     * @param target  the record to undo
     * @param related rows that may point at it: its corrections, and for a loan its
     *                repayments and their corrections. Rows that point elsewhere are
     *                ignored, so the history can pass its whole list.
     */
    public static Check check(LedgerTransaction target, List<LedgerTransaction> related) {
        throw new UnsupportedOperationException("UndoRules.check: yours to write");
    }
}
