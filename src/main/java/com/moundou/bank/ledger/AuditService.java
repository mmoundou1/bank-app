package com.moundou.bank.ledger;

import com.moundou.bank.identity.AdminService;
import com.moundou.bank.identity.MemberAdministration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The family administrator's view of the transactions and balance between any two
 * members, for dispute audit (Auth.Roles-10, UC-13). The only exception to SEC-2: no
 * other role can read a pair it is not part of.
 */
@Service
public class AuditService {

    /**
     * @param balances per currency, from {@code a}'s side: positive means {@code b} owes {@code a}
     */
    public record AuditView(MemberAdministration.MemberSummary a, MemberAdministration.MemberSummary b,
                            List<LedgerTransaction> transactions, Map<Currency, Long> balances) { }

    private final AdminService admin;
    private final MemberAdministration members;
    private final TransactionRepository transactions;

    public AuditService(AdminService admin, MemberAdministration members, TransactionRepository transactions) {
        this.admin = admin;
        this.members = members;
        this.transactions = transactions;
    }

    /**
     * @return empty when either member does not exist or both are the same member
     * @throws com.moundou.bank.NotPermittedException unless the actor is an administrator
     */
    @Transactional(readOnly = true)
    public Optional<AuditView> audit(UUID actorId, UUID a, UUID b) {
        admin.requireAdministrator(actorId, "audit two members");   // T-Roles-10b
        if (a == null || b == null || a.equals(b)) {
            return Optional.empty();
        }
        var memberA = members.findById(a);
        var memberB = members.findById(b);
        if (memberA.isEmpty() || memberB.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuditView(memberA.get(), memberB.get(),
                transactions.findBetween(a, b), transactions.pairBalances(a, b)));
    }
}
