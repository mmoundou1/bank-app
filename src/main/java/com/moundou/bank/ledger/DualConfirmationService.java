package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.notification.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DualConfirmationService {

    public sealed interface Outcome {
        record Recorded(LedgerTransaction transaction) implements DualConfirmationService.Outcome {
        }

        record Rejected(String reason) implements DualConfirmationService.Outcome {
        }
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
    public Outcome decline(UUID actorId, UUID transactionId, TransactionStatus status, String reason) {
        Member actor = members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        if (!(transactions.findById(transactionId)).isPresent()) {
            throw new NotPermittedException(transactionId, "Transaction id not found");
        } else {
            LedgerTransaction temp = transactions.findById(transactionId).get();

            if (actorId.equals(temp.initiatedBy()))
                throw new NotPermittedException(actorId, "Message");

            if (temp.status() == TransactionStatus.PENDING) {

                LedgerTransaction item = transactions.lockForUpdate(transactionId)
                        .orElseThrow(() -> new NotPermittedException(actorId, "decline transaction " + transactionId));

                transactions.recordHistory(transactionId, TransactionStatus.PENDING, TransactionStatus.DECLINED, actorId);
                transactions.recordDecision(transactionId, TransactionStatus.DECLINED, Instant.now(clock), reason);
                outbox.enqueue(transactionId, item.initiatedBy(), OutboxWriter.AlertKind.SETTLEMENT_ALERT);

                return new Outcome.Recorded(item);

            }
            else
                return new Outcome.Rejected("Not Pending");

        }

    }
}
