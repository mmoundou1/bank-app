package com.moundou.bank.ledger;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.notification.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

@Service
public class RepaymentService {

    public sealed interface Outcome {

        record Recorded(LedgerTransaction transaction, boolean alreadyRecorded) implements Outcome { }
        record Rejected(Map<String, String> fieldErrors) implements Outcome { }

    }

    public record RepaymentRequest(UUID loanId, String amountText, String currencyCode, UUID submissionKey) {}

    private final MemberDirectory members;
    private final TransactionRepository transactions;
    private final OutboxWriter outbox;
    private final Clock clock;

    RepaymentService(MemberDirectory members, TransactionRepository transactions, OutboxWriter outbox, Clock clock) {
        this.members = members;
        this.transactions = transactions;
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<LedgerTransaction> outstandingLoansOwedBy(UUID debtorId) {

        Member debtor = members.findById(debtorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(debtorId, "Member is inactive"));

        return transactions.outstandingLoansOwedBy(debtor.id());

    }

    public Optional<LedgerTransaction> viewTransaction(UUID debtorId, UUID loanId) {

        Member debtor = members.findById(debtorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(debtorId, "Member is inactive"));
        List<LedgerTransaction> list =  transactions.outstandingLoansOwedBy(loanId);

        if(!transactions.findById(loanId).orElseThrow().debtor().equals(debtor.id()))
            throw new NotPermittedException(debtor.id(), " is not owner of this loan");

        LedgerTransaction transaction = transactions.findById(loanId)
                                                .orElseThrow(() -> new NotPermittedException(loanId, ""));

        list.stream().filter(t -> t.id().equals(loanId)).findFirst();

        return Optional.of(transaction);
    }

    @Transactional
    public Outcome submit(UUID actorId, RepaymentRequest repaymentRequest, Locale locale) {

        UUID loanID = repaymentRequest.loanId();
        String amountText = repaymentRequest.amountText();
        String currencyText = repaymentRequest.currencyCode();
        Currency currency;
        Long amount;
        List<LedgerTransaction> list;
        Map<String, String> errors = new HashMap<>();
        Member debtor = members.findById(actorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));
        LocalDate todaysDate = LocalDate.now(clock.withZone(debtor.timeZone()));

        try {
            currency = Currency.getInstance(currencyText);
        }
        catch (IllegalArgumentException | NullPointerException e) {
            errors.put("currency", e.getMessage());
            return new Outcome.Rejected(errors);
        }

        if (repaymentRequest.submissionKey() != null) {
            Optional<LedgerTransaction> earlier = transactions
                                                        .findBySubmissionKey(actorId, repaymentRequest.submissionKey());
            if (earlier.isPresent())
                return new Outcome.Recorded(earlier.get(), true);
        }

        LedgerTransaction lockedItem = transactions.lockForUpdate(loanID)
                                                .orElseThrow(() -> new NotPermittedException(loanID, "does not exist."));

        list =  transactions.outstandingLoansOwedBy(loanID);
        list.stream().anyMatch(t -> t.id().equals(loanID));

        if(!transactions.findById(lockedItem.id()).orElseThrow().debtor().equals(debtor.id()))
            throw new NotPermittedException(actorId, " is not owner of this loan");

        switch(Amounts.parse(amountText, currency, locale)) {

            case Amounts.Parsed.Ok ok -> amount = ok.minorUnits();
            case Amounts.Parsed.Invalid invalid -> {
                errors.put("amount", invalid.messageKey());
                return new Outcome.Rejected(errors);
            }

        }

        if(amount != lockedItem.amountMinor()) {
            errors.put("amount", "ledger.repayment.amount.mismatch");
            return new Outcome.Rejected(errors);
        }

        if (!currency.equals(lockedItem.currency())) {
            errors.put("currency", "ledger.repayment.currency.mismatch");
            return new Outcome.Rejected(errors);
        }

        if(transactions.loanLifecycle(lockedItem.id()) != LoanLifecycle.OUTSTANDING) {
            errors.put("loan", "ledger.repayment.loan.notOutstanding");
            return new Outcome.Rejected(errors);
        }

        NewTransaction repayment = new NewTransaction(UUID.randomUUID(),
                TransactionKind.REPAYMENT,
                actorId,
                lockedItem.creditor(),
                amount,
                currency,
                todaysDate,
                null,
                actorId,
                loanID,
                null,
                false,
                repaymentRequest.submissionKey);

        if (!transactions.insertIfNew(repayment)) {
            return new Outcome.Recorded(
                    transactions.findBySubmissionKey(repayment.initiatedBy(), repayment.submissionKey()).orElseThrow(),
                                                                                                    true);
        }

        transactions.recordHistory(repayment.id(), null, TransactionStatus.PENDING, repayment.initiatedBy());
        UUID counterparty = repayment.initiatedBy().equals(repayment.creditor()) ?
                                                                            repayment.debtor() : repayment.creditor();
        outbox.enqueue(repayment.id(), counterparty, OutboxWriter.AlertKind.PENDING_ALERT);

        return new Outcome.Recorded(transactions.findById(repayment.id()).orElseThrow(), false);

    }

}
