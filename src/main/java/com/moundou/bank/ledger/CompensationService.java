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

/*

    - Display target details page (lent/borrowed; transaction amount; fixed amount; explanation message; “Ama confirms it first”;
    counterparty’s name; transaction type; transaction date; optional note)
    - Take in user input (target id, note, and submission key)
    - Check that the user is active
        - If user is inactive, throw NotPermittedException; otherwise, move to next step
    - Find the target
        - If target does not exist or the signed member isn’t creditor or debtor, throw NotPermittedException; otherwise, proceed to next step
    - Check that the target transaction is not a correction
        - If target transaction = CORRECTION, return Refused; otherwise, proceed to next step.
    - Lock target transaction
    - Check that the signed in member hasn’t already raised a correction against the target transaction
        - If this member already sent it, return Recorded with alreadyRecorded = true; otherwise, proceed to next step
    - Check that the target transaction is approved
        - If not Approved, return Refused; otherwise, proceed to next step.
    - Check that there’s no approved compensating entry against the target transaction
        - If there’s an approved compensating entry against the target transaction, return Refused
    - If target transaction is a loan, check that there's no approved repayment on it with a compensating entry being raised against it.
        - If the target is a loan, and it’s Settled, return Refused with the Ledger.History-11 message key; otherwise, proceed to next step.
    - Raise a compensating entry
        - Create new NewTransaction object with values for: transactionId, transaction kind (COMPENSATING),
        creditor (the debtor becomes the creditor), debtor (creditor becomes debtor), amount (copied from target),
        currency (copied from target), transactionDate (set to the transaction’s initiator’s date*), optional note, initiatorId,
        settlesTransactionId (set to null), correctsTransactionId (the original transaction’s id), openingBalance = false, submissionKey.
        - Add NewTransaction object to TransactionRepository
        - Create new recordHistory repository entry for the compensation transaction
    - Write PENDING_ALERT notification row to Outbox to target’s other party.
    - Return Recorded

 */

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

        checkClient(actorId, targetId);          // yours, kept: active, the record exists, a party to it
        Member actor = activeMember(actorId);    // for their time zone (CON-8)

        // CHANGED: every check below reads the row returned by the lock. The row read
        // before it can be out of date by the time the lock is granted (the MB-13 bug).
        LedgerTransaction locked = transactions.lockForUpdate(targetId)
                .orElseThrow(() -> new NotPermittedException(actorId, "view transaction " + targetId));

        // CHANGED: looked up by THIS form's key. item.submissionKey() was the target's
        // own key, from when the target was recorded, so a retry was never recognised.
        Optional<LedgerTransaction> earlier = transactions.findBySubmissionKey(actorId, submissionKey);
        if (earlier.isPresent())
            return new Outcome.Recorded(earlier.get(), true);

        // CHANGED: your four state checks are UndoRules.check, so call it instead of
        // repeating it. One rule, so the history's link and this submit cannot disagree.
        if (UndoRules.check(locked, transactions.relatedTo(locked.id())) instanceof UndoRules.Check.Refused refused)
            return new Outcome.Rejected(refused.messageKey());

        NewTransaction newTransaction = new NewTransaction(UUID.randomUUID(),
                                                            TransactionKind.COMPENSATING,
                                                            locked.debtor(),
                                                            locked.creditor(),
                                                            locked.amountMinor(),
                                                            locked.currency(),
                                                            LocalDate.now(clock.withZone(actor.timeZone())),
                                                            note == null || note.isBlank() ? null : note.strip(),  // CHANGED: as EntryService
                                                            actorId,
                                                            null,
                                                            locked.id(),
                                                            false,
                                                            submissionKey);                // CHANGED: the form's key

        // CHANGED: insertIfNew, as RepaymentService. If the same key still slipped in,
        // the earlier row is the answer, not a crash on the unique index.
        if (!transactions.insertIfNew(newTransaction))
            return new Outcome.Recorded(transactions.findBySubmissionKey(actorId, submissionKey).orElseThrow(), true);

        // CHANGED: the history row belongs to the NEW transaction, which starts life
        // pending (from nothing). Yours recorded the target moving APPROVED -> PENDING,
        // which never happened: the target is never changed (Ledger.History-5).
        transactions.recordHistory(newTransaction.id(), null, TransactionStatus.PENDING, actorId);

        UUID counterparty = newTransaction.initiatedBy().equals(newTransaction.creditor()) ?
                newTransaction.debtor() : newTransaction.creditor();                          // yours, kept
        outbox.enqueue(newTransaction.id(), counterparty, OutboxWriter.AlertKind.PENDING_ALERT);

        // CHANGED: return the new correction, not the target.
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

    /** CHANGED: your active-member lookup, moved here so both methods share it. */
    private Member activeMember(UUID actorId) {
        return members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));
    }

}
