package com.moundou.bank.ledger;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-Int-7a, scaffolded before the code it guards (MB-8): the zero-sum invariant
 * (INT-7), exercised by random sequences as INT-3 requires.
 *
 * Today it checks the MODEL: the direction convention of SRS 4.2 (CR-14, design D2),
 * where every approved row gives its creditor a claim of amount_minor on its debtor -
 * a loan lender->borrower, a repayment borrower->lender, a compensating entry the
 * corrected row's parties swapped. Under that convention a net position is one sum,
 * and the positions in each currency must add up to exactly zero.
 *
 * When MB-7 and the ledger services land, this becomes an integration test: generate
 * sequences of loans, opening balances, split entries, repayments, cancellations and
 * compensating entries through the services, then read the member_net view. The
 * property stays the same; only where the rows come from changes.
 */
class ZeroSumPropertyTest {

    enum Currency { USD, XAF, EUR }

    record ApprovedRow(int creditor, int debtor, long amountMinor, Currency currency) { }

    @Property(tries = 500)
    void netPositionsSumToZeroInEveryCurrency(@ForAll("approvedLedgers") List<ApprovedRow> ledger) {
        Map<Currency, Map<Integer, Long>> net = new EnumMap<>(Currency.class);
        for (ApprovedRow row : ledger) {
            Map<Integer, Long> positions = net.computeIfAbsent(row.currency(), c -> new HashMap<>());
            positions.merge(row.creditor(), row.amountMinor(), Long::sum);
            positions.merge(row.debtor(), -row.amountMinor(), Long::sum);
        }
        net.forEach((currency, positions) ->
                assertThat(positions.values().stream().mapToLong(Long::longValue).sum())
                        .as("sum of net positions in %s", currency)
                        .isZero());
    }

    @Provide
    Arbitrary<List<ApprovedRow>> approvedLedgers() {
        Arbitrary<Integer> member = Arbitraries.integers().between(1, 10);           // ten family accounts
        Arbitrary<Long> amount = Arbitraries.longs().between(1L, 1_000_000_000L);    // minor units, > 0 (Ledger.Entry-2)
        Arbitrary<Currency> currency = Arbitraries.of(Currency.class);
        return Combinators.combine(member, member, amount, currency)
                .as((creditor, debtor, amountMinor, cur) -> new ApprovedRow(creditor, debtor, amountMinor, cur))
                .filter(row -> row.creditor() != row.debtor())                       // Ledger.Entry-3
                .list().ofMaxSize(200);
    }
}
