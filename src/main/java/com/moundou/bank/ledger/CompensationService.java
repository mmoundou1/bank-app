package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.notification.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class CompensationService {

    public sealed interface Outcome {
        record Recorded(LedgerTransaction transaction, boolean alreadyRecorded) implements Outcome {
        }
        record Rejected(String reason) implements Outcome {
        }
    }

    /**
     * ADDED. What the confirm screen needs: the record, and whether it can be undone
     * (the same UndoRules answer the history and the submit use).
     */
    public record UndoTarget(LedgerTransaction target, UndoRules.Check check) {
    }

    private final MemberDirectory members;
    private final TransactionRepository transactions;
    private final Clock clock;
    private final OutboxWriter outbox;

    public CompensationService(MemberDirectory members, TransactionRepository transactions, Clock clock,
                                                                                                OutboxWriter outbox) {
        this.members = members;
        this.transactions = transactions;
        this.clock = clock;
        this.outbox = outbox;
    }

    /**
     * CHANGED. Returns the record with its UndoRules answer, so the screen can send the
     * member back when it cannot be undone. Optional.of(...) could never be empty,
     * because checkClient throws instead, so the Optional is gone.
     */
    @Transactional(readOnly = true)
    public UndoTarget targetDetails(UUID actorId, UUID targetId) {
        LedgerTransaction target = checkClient(actorId, targetId);
        return new UndoTarget(target, UndoRules.check(target, transactions.relatedTo(target.id())));
    }

    /**
     * Raises a compensating entry against {@code targetId} (Ledger.History-5, -8 to -11).
     *
     * CHANGED: @Transactional, without which the lock is released the moment it is
     * taken and the five writes below are not all-or-nothing. CHANGED: the submission
     * key is the form's, passed in, not the target's.
     */
    @Transactional
    public Outcome compensation(UUID actorId, UUID targetId, String note, UUID submissionKey) {
        Objects.requireNonNull(submissionKey, "submissionKey");

        checkClient(actorId, targetId);
        Member actor = activeMember(actorId);

        LedgerTransaction locked = transactions.lockForUpdate(targetId)
                .orElseThrow(() -> new NotPermittedException(actorId, "view transaction " + targetId));

        Optional<LedgerTransaction> earlier = transactions.findBySubmissionKey(actorId, submissionKey);
        if (earlier.isPresent())
            return new Outcome.Recorded(earlier.get(), true);

        if (UndoRules.check(locked, transactions.relatedTo(locked.id())) instanceof UndoRules.Check.Refused refused)
            return new Outcome.Rejected(refused.messageKey());

        NewTransaction newTransaction = new NewTransaction(UUID.randomUUID(),
                                                            TransactionKind.COMPENSATING,
                                                            locked.debtor(),
                                                            locked.creditor(),
                                                            locked.amountMinor(),
                                                            locked.currency(),
                                                            LocalDate.now(clock.withZone(actor.timeZone())),
                                                            note == null || note.isBlank() ? null : note.strip(),
                                                            actorId,
                                                            null,
                                                            locked.id(),
                                                            false,
                                                            submissionKey);

        if (!transactions.insertIfNew(newTransaction))
            return new Outcome.Recorded(transactions.findBySubmissionKey(actorId, submissionKey).orElseThrow(), true);

        transactions.recordHistory(newTransaction.id(), null, TransactionStatus.PENDING, actorId);

        UUID counterparty = newTransaction.initiatedBy().equals(newTransaction.creditor()) ?
                newTransaction.debtor() : newTransaction.creditor();                          // yours, kept
        outbox.enqueue(newTransaction.id(), counterparty, OutboxWriter.AlertKind.PENDING_ALERT);

        return new Outcome.Recorded(transactions.findById(newTransaction.id()).orElseThrow(), false);
    }

    private LedgerTransaction checkClient(UUID actorId, UUID targetId) {

        Member actor = activeMember(actorId);

        LedgerTransaction item = transactions.findById(targetId)
                .orElseThrow(() -> new NotPermittedException(actor.id(), "view transaction " + targetId));

        if(!(actor.id().equals(item.creditor()) || actor.id().equals(item.debtor())))
            throw new NotPermittedException(actor.id(), "view transaction " + targetId);

        return item;

    }

    private Member activeMember(UUID actorId) {
        return members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));
    }

}
