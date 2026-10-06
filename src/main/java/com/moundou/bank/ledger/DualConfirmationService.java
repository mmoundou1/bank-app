package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DualConfirmationService {

    public sealed interface Outcome {
        record Recorded() implements DualConfirmationService.Outcome { }
        record Rejected() implements DualConfirmationService.Outcome { }
    }

    private final TransactionRepository transactions;
    private final MemberDirectory members;
    private final Clock clock;

    public DualConfirmationService(TransactionRepository transactions, MemberDirectory members, Clock clock) {
        this.transactions = transactions;
        this.members = members;
        this.clock = clock;
    }

    public List<LedgerTransaction> awaitingDecisionBy(UUID memberId) {
        return transactions.awaitingDecisionBy(memberId);
    }

    @Transactional
    public Outcome approve(UUID actorId, UUID transactionId, String reason) {

    }

    @Transactional
    public Outcome decline(UUID actorId, LedgerTransaction transaction, String reason) {
        Member initiator = members.findById(actorId)
                .filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        if (!(transactions.findById(transaction.id())).isPresent()) {
            //Define the behavior
        } else {
            LedgerTransaction temp = transactions.findById(transaction.id()).get();

            if (temp.kind() == TransactionKind.LOAN) {
                if (!actorId.equals(temp.creditor()))
                    throw new NotPermittedException(actorId, "Message");
            } else if (temp.kind() == TransactionKind.REPAYMENT) {
                if (!actorId.equals(temp.debtor()))
                    throw new NotPermittedException(actorId, "Message");
            }

            if (temp.status() == TransactionStatus.PENDING) {
                LedgerTransaction temp2;

                if (transactions.lockForUpdate(temp.id()).isPresent()) {
                    temp2 = transactions.lockForUpdate(temp.id()).get();
                    transactions.recordDecision(temp2.id(), transaction.status(), Instant.now(clock), reason);
                }

                else {
                    //Need to model the Outcome object
                }
            }

        }
    }
}
