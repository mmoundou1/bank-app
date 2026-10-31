package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.notification.OutboxWriter;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
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

    public Optional<LedgerTransaction> targetDetails(UUID actorId, UUID targetId) {
        return Optional.of(checkClient(actorId, targetId));
    }

    public Outcome compensation(UUID actorId, UUID targetId, String note) {

        LedgerTransaction item = checkClient(actorId, targetId);

        if(item.kind() == TransactionKind.COMPENSATING)
            return new Outcome.Rejected("TBD");

        transactions.lockForUpdate(item.id());
        Optional<LedgerTransaction> earlier = transactions.findBySubmissionKey(actorId, item.submissionKey());

        if (earlier.isPresent())
            return new Outcome.Recorded(earlier.get(), true);
        if(item.status() != TransactionStatus.APPROVED)
            return new Outcome.Rejected("TBD");
        if(transactions.hasApprovedCorrection(item.id()))
            return new Outcome.Rejected("TBD");
        if(item.kind() == TransactionKind.LOAN && transactions.loanLifecycle(item.id()) == LoanLifecycle.SETTLED)
            return new Outcome.Rejected("TBD");

        Member actor = members.findById(actorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));
        NewTransaction newTransaction = new NewTransaction(UUID.randomUUID(),
                                                            TransactionKind.COMPENSATING,
                                                            item.debtor(),
                                                            item.creditor(),
                                                            item.amountMinor(),
                                                            item.currency(),
                                                            LocalDate.now(clock.withZone(actor.timeZone())),
                                                            note,
                                                            actorId,
                                                            null,
                                                            item.id(),
                                                            false,
                                                            UUID.randomUUID()
                                                            );

        transactions.insert(newTransaction);
        transactions.recordHistory(item.id(), TransactionStatus.APPROVED, TransactionStatus.PENDING, actorId);
        UUID counterparty = newTransaction.initiatedBy().equals(newTransaction.creditor()) ?
                newTransaction.debtor() : newTransaction.creditor();
        outbox.enqueue(newTransaction.id(), counterparty, OutboxWriter.AlertKind.PENDING_ALERT);

        return new Outcome.Recorded(item, false);

    }

    private LedgerTransaction checkClient(UUID actorId, UUID targetId) {

        Member actor = members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        LedgerTransaction item = transactions.findById(targetId)
                .orElseThrow(() -> new NotPermittedException(actor.id(), "view transaction " + targetId));

        if(!(actor.id().equals(item.creditor()) || actor.id().equals(item.debtor())))
            throw new NotPermittedException(actor.id(), "view transaction " + targetId);

        return item;

    }

}
