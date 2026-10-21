package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

// CHANGED (finding 1): without @Service, Spring never creates this class.
@Service
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

        // CHANGED (findings 2 and 3). The direction rule now lives in directionOf()
        // below, so the row and a correction's target share one copy of it.
        direction = directionOf(row, viewerId);

        // CHANGED (finding 2). The other party is on the row itself: whichever of
        // creditor and debtor is not the viewer. No map is needed for this.
        counterPartyId = row.creditor().equals(viewerId) ? row.debtor() : row.creditor();
        counterParty = names.get(counterPartyId);

        // CHANGED (finding 3). A correction's own direction is CORRECTION (set by
        // directionOf); targetDirection is the target's sentence for this viewer.
        if (row.kind() == TransactionKind.COMPENSATING) {
            LedgerTransaction target = rowsById.get(row.correctsTransactionId());
            targetDirection = directionOf(target, viewerId);
            targetDate = target.transactionDate();
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

    /**
     * ADDED (finding 3). Which of the five sentences {@code row} is for {@code viewerId}.
     * Your four loan and repayment cases, unchanged; only moved here so that toEntry can
     * use them twice. Creditor and debtor are this row's own effect (D2), so on a
     * repayment the creditor is the borrower: the one who paid.
     */
    private static HistoryDirection directionOf(LedgerTransaction row, UUID viewerId) {
        boolean viewerIsCreditor = row.creditor().equals(viewerId);
        if (row.kind() == TransactionKind.LOAN)
            return viewerIsCreditor ? HistoryDirection.YOU_LENT : HistoryDirection.YOU_BORROWED;
        if (row.kind() == TransactionKind.REPAYMENT)
            return viewerIsCreditor ? HistoryDirection.YOU_REPAID : HistoryDirection.YOU_WERE_REPAID;
        return HistoryDirection.CORRECTION;
    }

}
