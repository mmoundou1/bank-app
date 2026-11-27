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
import java.util.stream.Collectors;

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

        List<LedgerTransaction> outstandingLoansOwedBy =  transactions.outstandingLoansOwedBy(debtor.id());

        Set<UUID> activeCreditorIds = outstandingLoansOwedBy.stream()
                .map(LedgerTransaction::creditor)
                .distinct()
                .filter(creditorId -> members.findById(creditorId)
                        .map(Member::active)
                        .orElse(false))
                .collect(Collectors.toSet());

        return outstandingLoansOwedBy.stream()
                .filter(tx -> activeCreditorIds.contains(tx.creditor()))
                .toList();

    }

    public Optional<LedgerTransaction> viewTransaction(UUID debtorId, UUID loanId) {

        Member debtor = members.findById(debtorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(debtorId, "Member is inactive"));
        List<LedgerTransaction> outstandingLoansOwedBy =  this.outstandingLoansOwedBy(debtorId);

        Set<UUID> activeCreditorIds = outstandingLoansOwedBy.stream()
                .map(LedgerTransaction::creditor)
                .distinct()
                .filter(creditorId -> members.findById(creditorId)
                        .map(Member::active)
                        .orElse(false))
                .collect(Collectors.toSet());

        return outstandingLoansOwedBy.stream()
                                .filter(tx -> activeCreditorIds.contains(tx.creditor())).findFirst();

    }

    @Transactional
    public Outcome submit(UUID actorId, RepaymentRequest repaymentRequest, Locale locale) {

        UUID loanID = repaymentRequest.loanId();
        String amountText = repaymentRequest.amountText();
        String currencyText = repaymentRequest.currencyCode();
        Currency currency;
        long amount;
        List<LedgerTransaction> outstandingLoansOwedBy;
        Map<String, String> errors = new HashMap<>();
        boolean viewerIsBorrower = false;
        boolean lenderActive = false;
        boolean repaymentWaiting = false;


        Member member = members.findById(actorId).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(actorId, "Member is inactive"));

        LocalDate todaysDate = LocalDate.now(clock.withZone(member.timeZone()));

        try {
            currency = Currency.getInstance(currencyText);
        }
        catch (IllegalArgumentException | NullPointerException e) {
            errors.put("currency", "ledger.repayment.currency.mismatch");
            return new Outcome.Rejected(errors);
        }

        LedgerTransaction lockedItem = transactions.lockForUpdate(loanID)
                                                .orElseThrow(() -> new NotPermittedException(loanID, "does not exist."));

        if(lockedItem.debtor().equals(member.id()))
            viewerIsBorrower = true;

        Member creditor = members.findById(lockedItem.creditor()).filter(Member::active)
                .orElseThrow(() -> new NotPermittedException(lockedItem.creditor(), "Member is inactive"));

        lenderActive = true;

        if (repaymentRequest.submissionKey() != null) {
            Optional<LedgerTransaction> earlier = transactions
                    .findBySubmissionKey(actorId, repaymentRequest.submissionKey());
            if (earlier.isPresent()) {
                return new Outcome.Recorded(earlier.get(), true);
            }
        }

        outstandingLoansOwedBy =  transactions.outstandingLoansOwedBy(actorId);

        if(!outstandingLoansOwedBy.stream().anyMatch(t -> t.id().equals(loanID))) {

            repaymentWaiting = true;
            errors.put("loan", "ledger.repayment.loan.notOutstanding");

            return new Outcome.Rejected(errors);

        }

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
                repaymentRequest.submissionKey());

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
