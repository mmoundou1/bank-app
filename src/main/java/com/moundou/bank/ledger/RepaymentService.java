package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.notification.OutboxWriter;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class RepaymentService {

    public sealed interface Outcome {

        record Recorded(LedgerTransaction transaction, boolean alreadyRecorded) implements Outcome { }
        record Rejected(Map<String, String> fieldErrors) implements Outcome { }

    }

    public record RepaymentRequest(UUID loanId, String amountText, String currencyCode, UUID submissionKey) {}

    MemberDirectory members;
    TransactionRepository transactions;
    private final OutboxWriter outbox;

    RepaymentService(MemberDirectory members, TransactionRepository transactions, OutboxWriter outbox) {
        this.members = members;
        this.transactions = transactions;
        this.outbox = outbox;
    }

    public Optional<List<LedgerTransaction>> outstandingLoansOwedBy(UUID debtorId) {

        Member debtor = members.findById(debtorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(debtorId, "Member is inactive"));

        return Optional.of(transactions.outstandingLoansOwedBy(debtor.id()));

    }

    public Optional<LedgerTransaction> viewTransaction(UUID debtorId, UUID loanId) {

        Member debtor = members.findById(debtorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(debtorId, "Member is inactive"));
        LedgerTransaction transaction = transactions.findById(loanId)
                                                .orElseThrow(() -> new NotPermittedException(loanId, ""));

        return Optional.of(transaction);
    }

    public Outcome submit(UUID actorId, RepaymentRequest repaymentRequest, Locale locale) {

        UUID loanID = repaymentRequest.loanId();
        String amountText = repaymentRequest.amountText();
        long amount = Long.parseLong(amountText);
        String currencyText = repaymentRequest.currencyCode();
        Currency currency = Currency.getInstance(currencyText);
        Map<String, String> errors = new HashMap<>();
        Member debtor = members.findById(actorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));
        LedgerTransaction item = transactions.findById(loanID).orElseThrow();

        if(!transactions.findById(loanID).orElseThrow().debtor().equals(debtor.id()))
            throw new NotPermittedException(actorId, "Debtor is not owner of this loan");

        transactions.lockForUpdate(loanID);

        if(amount != item.amountMinor()) {
            errors.put("", "Amount must settle loan in full.");
            return new Outcome.Rejected(errors);
        }

        if (!currency.equals(item.currency())) {
            errors.put("", "Repayment must be in the loan's currency.");
            return new Outcome.Rejected(errors);
        }

        if(item.kind() != TransactionKind.LOAN) {
            errors.put("", "This loan is no longer outstanding.");
            return new Outcome.Rejected(errors);
        }

        NewTransaction repayment = new NewTransaction(UUID.randomUUID(),
                TransactionKind.REPAYMENT,
                actorId,
                item.creditor(),
                amount,
                currency,
                EntryValidator.today(debtor),
                null,
                actorId,
                loanID,
                null,
                false,
                UUID.randomUUID());

        if (!transactions.insertIfNew(repayment)) {
            return new Outcome.Recorded(
                    transactions.findBySubmissionKey(repayment.initiatedBy(), repayment.submissionKey()).orElseThrow(),
                                                                                                    true);
        }

        transactions.recordHistory(repayment.id(), null, TransactionStatus.PENDING, repayment.initiatedBy());
        UUID counterparty = repayment.initiatedBy().equals(repayment.creditor()) ? repayment.debtor() : repayment.creditor();
        outbox.enqueue(repayment.id(), counterparty, OutboxWriter.AlertKind.PENDING_ALERT);

        return new Outcome.Recorded(transactions.findById(repayment.id()).orElseThrow(), false);

    }

}
