package com.moundou.bank.ledger;

import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.notification.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Records a new loan, split-the-bill entry or opening balance (SRS 3.1, UC-01,
 * Technical Design 4 "Submit").
 *
 * One database transaction writes three rows or none:
 * <ol>
 *   <li>the transaction, Pending Approval (Ledger.Entry-4). No balance changes, because
 *       balances are derived from approved rows only (Ledger.Entry-5, ADR-002);</li>
 *   <li>its first history row, created as pending by the initiator (Data-5);</li>
 *   <li>an outbox row alerting the counterparty (Ledger.Entry-6, Notify.Pending-1).
 *       Sending happens after commit, so the request never waits on email (PERF-1).</li>
 * </ol>
 * A retried submission returns the record the first attempt created and writes nothing
 * (Ledger.Entry-14).
 */
@Service
public class EntryService {

    public sealed interface Outcome {
        /** Saved, or found already saved by an earlier attempt of the same submission. */
        record Recorded(LedgerTransaction transaction, boolean alreadyRecorded) implements Outcome { }
        /** Nothing saved. Field name to message key, for inline display (UI-4). */
        record Rejected(Map<String, String> fieldErrors) implements Outcome { }
    }

    private final MemberDirectory members;
    private final TransactionRepository transactions;
    private final OutboxWriter outbox;
    private final EntryValidator validator;

    public EntryService(MemberDirectory members, TransactionRepository transactions,
                        OutboxWriter outbox, EntryValidator validator) {
        this.members = members;
        this.transactions = transactions;
        this.outbox = outbox;
        this.validator = validator;
    }

    /**
     * @param initiatorId the signed-in member; from the session, never from the form
     * @param locale      how the amount was typed; English in Release 1.0 (ADR-014)
     * @throws NotPermittedException if the initiator is unknown or deactivated
     */
    @Transactional
    public Outcome submit(UUID initiatorId, EntryRequest request, Locale locale) {
        Member initiator = members.findById(initiatorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(initiatorId, "record a transaction"));

        // A retry of a submission that already succeeded gets the same answer, even if
        // something has changed since (the counterparty deactivated, the day turned).
        if (request.submissionKey() != null) {
            Optional<LedgerTransaction> earlier = transactions.findBySubmissionKey(initiatorId, request.submissionKey());
            if (earlier.isPresent()) {
                return new Outcome.Recorded(earlier.get(), true);
            }
        }

        Optional<Member> counterparty = request.counterpartyId() == null
                ? Optional.empty()
                : members.findById(request.counterpartyId());

        return switch (validator.validate(initiator, counterparty, request, locale)) {
            case EntryValidator.Result.Invalid invalid -> new Outcome.Rejected(invalid.fieldErrors());
            case EntryValidator.Result.Valid valid -> record(valid.transaction());
        };
    }

    private Outcome record(NewTransaction t) {
        if (!transactions.insertIfNew(t)) {
            // Two retries raced past the check above; the other one saved it.
            return new Outcome.Recorded(
                    transactions.findBySubmissionKey(t.initiatedBy(), t.submissionKey()).orElseThrow(), true);
        }
        transactions.recordHistory(t.id(), null, TransactionStatus.PENDING, t.initiatedBy());
        UUID counterparty = t.initiatedBy().equals(t.creditor()) ? t.debtor() : t.creditor();
        outbox.enqueue(t.id(), counterparty, OutboxWriter.AlertKind.PENDING_ALERT);
        return new Outcome.Recorded(transactions.findById(t.id()).orElseThrow(), false);
    }
}
