package com.moundou.bank.ledger;

import java.util.UUID;

public enum HistoryDirection {
    YOU_LENT,
    YOU_BORROWED,
    YOU_REPAID,
    YOU_WERE_REPAID,
    CORRECTION;

    /**
     * Which sentence {@code row} is for {@code viewerId}, shared by the history and the
     * undo screen. Creditor and
     * debtor are this row's own effect (D2), so on a repayment the creditor is the
     * borrower: the one who paid.
     */
    public static HistoryDirection of(LedgerTransaction row, UUID viewerId) {
        boolean viewerIsCreditor = row.creditor().equals(viewerId);
        if (row.kind() == TransactionKind.LOAN)
            return viewerIsCreditor ? YOU_LENT : YOU_BORROWED;
        if (row.kind() == TransactionKind.REPAYMENT)
            return viewerIsCreditor ? YOU_REPAID : YOU_WERE_REPAID;
        return CORRECTION;
    }
}
