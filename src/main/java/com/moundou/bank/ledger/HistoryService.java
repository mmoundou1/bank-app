package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 *
 */

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
        Map<UUID, LedgerTransaction> rowsById = rows.stream()
                .collect(Collectors.toMap(
                        LedgerTransaction::id,
                        Function.identity()));

        for (LedgerTransaction row : rows)
            history.add(toEntry(row, viewer.id(), rows, rowsById, names));

        return history;

    }

    private static HistoryEntry toEntry(LedgerTransaction row, UUID viewerId, List<LedgerTransaction> rows,
                                                        Map<UUID, LedgerTransaction> rowsById, Map<UUID, String> names) {

        HistoryDirection direction = null;
        HistoryDirection targetDirection = null;
        LocalDate targetDate = null;
        UUID counterPartyId;
        String counterParty = null;
        boolean recordedByYou = false;

        // One direction rule, for the row and for a correction's target.
        direction = HistoryDirection.of(row, viewerId);

        // The other party: whichever of creditor and debtor is not the viewer.
        counterPartyId = row.creditor().equals(viewerId) ? row.debtor() : row.creditor();
        counterParty = names.get(counterPartyId);

        // A correction's own direction is CORRECTION (set by
        // directionOf); targetDirection is the target's sentence for this viewer.
        if (row.kind() == TransactionKind.COMPENSATING) {
            LedgerTransaction target = rowsById.get(row.correctsTransactionId());
            targetDirection = HistoryDirection.of(target, viewerId);
            targetDate = target.transactionDate();
        }

        if(row.initiatedBy().equals(viewerId))
            recordedByYou = true;

        return new HistoryEntry(direction,
                counterParty,
                recordedByYou,
                row.amountMinor(),
                row.currency(),
                row.transactionDate(),
                row.note(),
                row.status(),
                row.declineReason(),
                row.decidedAt(),
                row.openingBalance(),
                targetDirection,
                targetDate,
                row.id(),
                undoLink(row, rows));

    }

    /**
     * The undo link for one card: UndoRules decides whether the record
     * can be undone, using the whole history (it ignores rows that point elsewhere), and
     * the link is also hidden while a correction of it is pending (decision 3, display
     * only, so it is not part of UndoRules).
     */
    private static HistoryEntry.UndoLink undoLink(LedgerTransaction row, List<LedgerTransaction> rows) {
        boolean correctionPending = rows.stream()
                .anyMatch(tx -> tx.kind() == TransactionKind.COMPENSATING
                        && tx.status() == TransactionStatus.PENDING
                        && row.id().equals(tx.correctsTransactionId()));
        if (correctionPending)
            return HistoryEntry.UndoLink.NONE;
        return switch (UndoRules.check(row, rows)) {
            case UndoRules.Check.Undoable undoable -> HistoryEntry.UndoLink.SHOW;
            case UndoRules.Check.Refused refused ->
                    "ledger.history.correction.conflictsWithRepayment".equals(refused.messageKey())
                            ? HistoryEntry.UndoLink.REPAYMENT_FIRST
                            : HistoryEntry.UndoLink.NONE;
        };
    }

}
