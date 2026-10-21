package com.moundou.bank.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;

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
                           LocalDate targetDate) {
}