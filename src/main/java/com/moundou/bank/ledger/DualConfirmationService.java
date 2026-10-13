package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.notification.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class DualConfirmationService {

    public sealed interface Outcome {
        record Recorded(LedgerTransaction transaction) implements DualConfirmationService.Outcome {
        }
        record Rejected(String reason) implements DualConfirmationService.Outcome {
        }
    }

    public record View(LedgerTransaction transaction, Map<UUID, String> partiesMap) {}

    public record TransactionQueue(List<LedgerTransaction> toDecide, List<LedgerTransaction> waiting,
                                                                                            Map<UUID, String> names) {}

    private final TransactionRepository transactions;
    private final MemberDirectory members;
    private final Clock clock;
    private final OutboxWriter outbox;

    public DualConfirmationService(TransactionRepository transactions, MemberDirectory members, Clock clock,  OutboxWriter outbox) {
        this.transactions = transactions;
        this.members = members;
        this.clock = clock;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome approve(UUID actorId, UUID transactionId) {

        Member actor = members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        LedgerTransaction item = transactions.findById(transactionId)
                            .orElseThrow(() -> new NotPermittedException(actorId, "approve transaction " + transactionId));

        UUID correctsTransactionId = item.correctsTransactionId();
        UUID loanId = item.settlesTransactionId();
        LedgerTransaction target = null;

        if(correctsTransactionId != null) {
            target = transactions.lockForUpdate(correctsTransactionId)
                        .orElseThrow(() -> new NotPermittedException(actorId, "correct a transaction " + transactionId));
        }

        if(loanId != null)
            transactions.lockForUpdate(loanId)
                        .orElseThrow(() -> new NotPermittedException(actorId, "settle a transaction " + transactionId));


        LedgerTransaction lockedItem = transactions.lockForUpdate(transactionId)
                .orElseThrow(() -> new NotPermittedException(actorId, "approve transaction " + transactionId));

        TransactionStateMachine.Role role = TransactionStateMachine.roleOf(actor.id(), lockedItem);

        TransactionStateMachine.Transition transition = TransactionStateMachine.next(lockedItem.status(),
                TransactionStateMachine.Event.APPROVE, role);

        switch (transition) {
            case TransactionStateMachine.Transition.AllowedTo allowedTo -> {

                if (loanId != null && transactions.loanLifecycle(loanId) != LoanLifecycle.OUTSTANDING)
                    return new Outcome.Rejected("ledger.repayment.loan.notOutstanding");

                if(correctsTransactionId != null) {
                    if (transactions.hasApprovedCorrection(correctsTransactionId))
                        return new Outcome.Rejected("ledger.history.correction.alreadyCorrected");

                    if (target.kind() == TransactionKind.LOAN
                            && transactions.loanLifecycle(target.id()) == LoanLifecycle.SETTLED)
                        return new Outcome.Rejected("ledger.history.correction.conflictsWithRepayment");
                }

                transactions.recordHistory(transactionId, TransactionStatus.PENDING, allowedTo.to(), actorId);
                transactions.recordDecision(transactionId, allowedTo.to(), Instant.now(clock), null);
                outbox.enqueue(transactionId, item.initiatedBy(), OutboxWriter.AlertKind.SETTLEMENT_ALERT);

                return new Outcome.Recorded(lockedItem);
            }

            case TransactionStateMachine.Transition.NotPending notPending -> {
                return new Outcome.Rejected("ledger.approval.notPending");
            }

            case TransactionStateMachine.Transition.WrongActor wrongActor ->
                    throw new NotPermittedException(actorId, "Action not permitted");

        }
    }

    @Transactional
    public Outcome decline(UUID actorId, UUID transactionId, String reason) {

        Member actor = members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        LedgerTransaction item = transactions.lockForUpdate(transactionId)
                    .orElseThrow(() -> new NotPermittedException(actorId, "decline transaction " + transactionId));

        TransactionStateMachine.Role role = TransactionStateMachine.roleOf(actor.id(), item);

        TransactionStateMachine.Transition transition = TransactionStateMachine.next(item.status(),
                                        TransactionStateMachine.Event.DECLINE, role);

        switch (transition) {
            case TransactionStateMachine.Transition.AllowedTo allowedTo -> {
                if(reason == null || reason.isBlank())
                    reason = null;

                transactions.recordHistory(transactionId, TransactionStatus.PENDING, allowedTo.to(), actorId);
                transactions.recordDecision(transactionId, allowedTo.to(), Instant.now(clock), reason);
                outbox.enqueue(transactionId, item.initiatedBy(), OutboxWriter.AlertKind.SETTLEMENT_ALERT);

                return new Outcome.Recorded(item);
            }

            case TransactionStateMachine.Transition.NotPending notPending -> {
                return new Outcome.Rejected("ledger.approval.notPending");
            }

            case TransactionStateMachine.Transition.WrongActor wrongActor ->
                throw new NotPermittedException(actorId, "Action not permitted");

        }

    }

    public View view(UUID actorId, UUID transactionId) {
        Member actor = members.findById(actorId)
                                    .filter(Member::active)
                                    .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));
        LedgerTransaction transaction = transactions
                                .findById(transactionId)
                                .orElseThrow(() -> new NotPermittedException(actorId, "Transaction not found"));

        boolean isParty = actor.id().equals(transaction.creditor())
                                                                    || actor.id().equals(transaction.debtor());
        if (!isParty)
            throw new NotPermittedException(actor.id(), "Action not permitted");

        Member creditor = members.findById(transaction.creditor()).orElseThrow();
        Member debtor = members.findById(transaction.debtor()).orElseThrow();
        Map<UUID, String> names = Map.of(
                transaction.creditor(), creditor.displayName(),
                transaction.debtor(), debtor.displayName());

        return new DualConfirmationService.View(transaction, names);

    }

    public TransactionQueue queue(UUID actorId) {
        Map<UUID, String> names = new HashMap<>();

        Member actor = members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        Set<UUID> allIds = new HashSet<>();

        List<LedgerTransaction> toDecide = transactions.awaitingDecisionBy(actor.id());
        for (LedgerTransaction item : toDecide) {
            allIds.add(item.creditor());
            allIds.add(item.debtor());
        }

        List<LedgerTransaction> waiting = transactions.awaitingOthersFor(actor.id());
        for (LedgerTransaction item : waiting) {
            allIds.add(item.creditor());
            allIds.add(item.debtor());
        }

        for (UUID id : allIds) {
            Member member = members.findById(id).orElseThrow();
            String displayName = member.displayName();
            names.put(id, displayName);
        }

        return new DualConfirmationService.TransactionQueue(toDecide, waiting, names);
    }

    @Transactional
    public Outcome cancel(UUID actorId, UUID transactionId) {
        Member actor = members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        LedgerTransaction item = transactions.lockForUpdate(transactionId)
                .orElseThrow(() -> new NotPermittedException(actorId, "cancel " + transactionId));

        TransactionStateMachine.Role role = TransactionStateMachine.roleOf(actor.id(), item);

        TransactionStateMachine.Transition transition = TransactionStateMachine.next(item.status(),
                TransactionStateMachine.Event.CANCEL, role);

        switch (transition) {

            case TransactionStateMachine.Transition.AllowedTo allowedTo -> {

                UUID counterpartyId = null;

                if(item.creditor().equals(actor.id()))
                    counterpartyId = item.debtor();
                else
                    counterpartyId = item.creditor();

                transactions.recordHistory(transactionId, TransactionStatus.PENDING, allowedTo.to() , actorId);
                transactions.recordDecision(transactionId, allowedTo.to(), Instant.now(clock), null);
                outbox.enqueue(transactionId, counterpartyId, OutboxWriter.AlertKind.CANCELLATION_ALERT);

                return new Outcome.Recorded(item);
            }

            case TransactionStateMachine.Transition.NotPending notPending -> {
                return new Outcome.Rejected("ledger.approval.notPending");
            }

            case TransactionStateMachine.Transition.WrongActor wrongActor ->
                    throw new NotPermittedException(actorId, "Action not permitted");

        }

    }

}
