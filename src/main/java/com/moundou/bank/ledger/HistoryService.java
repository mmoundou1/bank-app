package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class HistoryService {

    private final TransactionRepository transactions;
    private final MemberDirectory members;

    HistoryService(TransactionRepository transactions, MemberDirectory members) {
        this.transactions = transactions;
        this.members = members;
    }

    public List<HistoryEntry> historyFor(UUID viewerId) {

        Member viewer = members.findById(viewerId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(viewerId, "Member is inactive"));

        List<LedgerTransaction> rows = transactions.historyFor(viewer.id());
        Map<UUID, String> names = members.displayNames();
        List<HistoryEntry> history = new ArrayList<>();
        Map<UUID, LedgerTransaction> rowsById = rows.stream()             // 2. go through them one by one
                .collect(Collectors.toMap(                                // 3. gather them into a map
                        LedgerTransaction::id,                            //    key:   the row's id
                        Function.identity()));

        for (LedgerTransaction row : rows)
            history.add(toEntry(row, viewer.id(), rowsById, names));

        return history;

    }

    private static HistoryEntry toEntry(LedgerTransaction row, UUID viewerId,
                                                        Map<UUID, LedgerTransaction> rowsById, Map<UUID, String> names) {
        HistoryEntry entry;
        HistoryDirection direction = null;
        HistoryDirection targetDirection = null;
        LocalDate targetDate = null;
        UUID counterPartyId;
        String counterParty = null;
        boolean recordedByYou = false;
        Long amountMinor;
        Currency currency;
        LocalDate transactionDate;
        String note;
        TransactionStatus status;
        String declineReason;
        Instant decidedAt;
        boolean openingBalance;

        if(row.kind() == TransactionKind.LOAN) {
            if(row.creditor().equals(viewerId)) {
                direction = HistoryDirection.YOU_LENT;
                counterPartyId = rowsById.get(viewerId).debtor();
                counterParty = names.get(counterPartyId);
            }

            else if(row.debtor().equals(viewerId)) {
                direction = HistoryDirection.YOU_BORROWED;
                counterPartyId = rowsById.get(viewerId).creditor();
                counterParty = names.get(counterPartyId);
            }
        }

        else if(row.kind() == TransactionKind.REPAYMENT) {
            if(row.creditor().equals(viewerId)) {
                direction = HistoryDirection.YOU_REPAID;
                counterPartyId = rowsById.get(viewerId).debtor();
                counterParty = names.get(counterPartyId);
            }

            else if(row.debtor().equals(viewerId)) {
                direction = HistoryDirection.YOU_WERE_REPAID;
                counterPartyId = rowsById.get(viewerId).creditor();
                counterParty = names.get(counterPartyId);
            }
        }

        else if(row.kind() == TransactionKind.COMPENSATING) {
            targetDirection = HistoryDirection.CORRECTION;
            UUID correctsTransactionId = row.correctsTransactionId();
            targetDate = rowsById.get(correctsTransactionId).transactionDate();
        }

        if(row.initiatedBy().equals(viewerId))
            recordedByYou = true;

        amountMinor = row.amountMinor();
        currency = row.currency();
        transactionDate = row.transactionDate();
        note = row.note();
        status = row.status();
        declineReason = row.declineReason();
        decidedAt = row.decidedAt();
        openingBalance = row.openingBalance();

        entry = new HistoryEntry(direction,
                counterParty,
                recordedByYou,
                amountMinor,
                currency,
                transactionDate,
                note,
                status,
                declineReason,
                decidedAt,
                openingBalance,
                targetDirection,
                targetDate);


        return entry;

    }

}
