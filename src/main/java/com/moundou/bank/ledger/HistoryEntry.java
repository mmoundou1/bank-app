package com.moundou.bank.ledger;

import java.time.LocalDate;
import java.util.Currency;

public record HistoryEntry(HistoryDirection direction,
                           String otherPartyName,
                           boolean recordedByYou,
                           long amountMinor,
                           Currency currency,
                           LocalDate transactionDate,
                           String note,
                           TransactionStatus status,
                           String declineReason,
                           LocalDate decidedAt,
                           boolean openingBalance,
                           HistoryDirection targetDirection,
                           LocalDate targetDate) {
}