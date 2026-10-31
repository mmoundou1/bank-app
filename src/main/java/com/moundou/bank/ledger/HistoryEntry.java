package com.moundou.bank.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

/**
 *
 * @param direction
 * @param otherPartyName
 * @param recordedByYou
 * @param amountMinor
 * @param currency
 * @param transactionDate
 * @param note - may contain null value
 * @param status
 * @param declineReason - may contain null value
 * @param decidedAt - null while the transaction status is "Pending"
 * @param openingBalance
 * @param targetDirection - null, unless @param direction is set to CORRECTION
 * @param targetDate - null, unless @param direction is set to CORRECTION
 * @param id - the transaction's id, for the undo link (MB-14 journey 2)
 * @param undo - what the card offers for undoing it
 */

public record HistoryEntry(HistoryDirection direction,
                           String otherPartyName,
                           boolean recordedByYou,
                           long amountMinor,
                           Currency currency,
                           LocalDate transactionDate,
                           String note,
                           TransactionStatus status,
                           String declineReason,
                           Instant decidedAt,
                           boolean openingBalance,
                           HistoryDirection targetDirection,
                           LocalDate targetDate,
                           UUID id,
                           UndoLink undo) {

    /**
     * What a card offers for undoing its record. The service decides; the template only
     * shows it.
     */
    public enum UndoLink {
        /** "Undo this record": UndoRules says Undoable and no correction is pending. */
        SHOW,
        /** A loan still settled by a repayment: the hint to undo the repayment first (decision 1). */
        REPAYMENT_FIRST,
        /** Nothing: a correction, a record that never counted, already undone, or one pending (decision 3). */
        NONE
    }
}