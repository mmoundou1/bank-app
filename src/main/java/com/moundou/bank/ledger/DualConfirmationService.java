package com.moundou.bank.ledger;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class DualConfirmationService {

    private final TransactionRepository transactions;

    public DualConfirmationService(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    public List<LedgerTransaction> awaitingDecisionBy(UUID memberId) {
        return transactions.awaitingDecisionBy(memberId);
    }

    public void approveTransaction(UUID memberId, LedgerTransaction transaction) {}

    public void declineTransaction(UUID memberId, LedgerTransaction transaction) {}

}
