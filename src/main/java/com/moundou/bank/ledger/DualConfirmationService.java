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

    public sealed interface TransactionObject {
        record TransactionDetails(LedgerTransaction transaction, Map<String, UUID> partiesMap)
                                                                implements DualConfirmationService.TransactionObject {}
    }

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

    public List<LedgerTransaction> awaitingDecisionBy(UUID memberId) {
        return transactions.awaitingDecisionBy(memberId);
    }

    @Transactional
    public Outcome approve(UUID actorId, UUID transactionId) {
        throw new UnsupportedOperationException("To be implemented");
    }

    @Transactional
    public Outcome decline(UUID actorId, UUID transactionId, String reason) {
        Member actor = members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        LedgerTransaction item = transactions.lockForUpdate(transactionId)
                    .orElseThrow(() -> new NotPermittedException(actorId, "decline transaction " + transactionId));

        boolean isParty = actorId.equals(item.creditor()) || actorId.equals(item.debtor());
        if (!isParty || actorId.equals(item.initiatedBy()))
            throw new NotPermittedException(actorId, "Action not permitted");

        if (item.status() == TransactionStatus.PENDING) {
            if(reason == null || reason.isBlank())
                reason = null;

            transactions.recordHistory(transactionId, TransactionStatus.PENDING, TransactionStatus.DECLINED, actorId);
            transactions.recordDecision(transactionId, TransactionStatus.DECLINED, Instant.now(clock), reason);
            outbox.enqueue(transactionId, item.initiatedBy(), OutboxWriter.AlertKind.SETTLEMENT_ALERT);

            return new Outcome.Recorded(item);
        }
        else
            return new Outcome.Rejected("ledger.approval.notPending");
    }

    public TransactionObject.TransactionDetails view(UUID counterpartyId, UUID actorId) {
        Member counterparty = members.findById(counterpartyId)
                                    .filter(Member::active)
                                    .orElseThrow(() -> new NotPermittedException(counterpartyId, "Member is inactive"));
        LedgerTransaction transaction = transactions
                                .findById(counterparty.id())
                                .orElseThrow(() -> new NotPermittedException(counterpartyId, "Transaction not found"));

        boolean isActor = actorId.equals(transaction.initiatedBy());

        if (!isActor)
            throw new NotPermittedException(counterpartyId, "Action not permitted");

        Member actorMember = members.findById(actorId).get();
        Member counterpartyMember = members.findById(counterparty.id()).get();
        Map<String, UUID> partiesMap = new HashMap<>();

        partiesMap.put(counterpartyMember.displayName(), counterparty.id());
        partiesMap.put(actorMember.displayName(), actorId);

        return new DualConfirmationService.TransactionObject.TransactionDetails(transaction, partiesMap);

    }
}
