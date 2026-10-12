package com.moundou.bank.ledger;

import com.moundou.bank.ledger.TransactionStateMachine.Event;
import com.moundou.bank.ledger.TransactionStateMachine.Role;
import com.moundou.bank.ledger.TransactionStateMachine.Transition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. Every (status, event, role) combination against the table in Technical
 * Design 5: 4 x 3 x 3 = 36 cases, each reported on its own.
 *
 * The expected answers are written out here as the table, not computed the way the
 * class computes them, so a mistake in the class cannot hide behind the same mistake
 * in the test.
 */
class TransactionStateMachineTests {

    /** The three legal transitions (Model 5, T2 to T4). Everything else is refused. */
    static final Map<List<Object>, TransactionStatus> ALLOWED = Map.of(
            List.of(TransactionStatus.PENDING, Event.APPROVE, Role.COUNTERPARTY), TransactionStatus.APPROVED,  // Ledger.Approval-3, -4
            List.of(TransactionStatus.PENDING, Event.DECLINE, Role.COUNTERPARTY), TransactionStatus.DECLINED,  // Ledger.Approval-3, -7
            List.of(TransactionStatus.PENDING, Event.CANCEL, Role.INITIATOR), TransactionStatus.CANCELLED);    // Ledger.Approval-11, -12

    /** Who may ever do each event, whatever the status (Ledger.Approval-3, -12). */
    static final Map<Event, Role> ONLY = Map.of(
            Event.APPROVE, Role.COUNTERPARTY,
            Event.DECLINE, Role.COUNTERPARTY,
            Event.CANCEL, Role.INITIATOR);

    static Stream<Arguments> everyCombination() {
        List<Arguments> all = new ArrayList<>();
        for (TransactionStatus from : TransactionStatus.values())
            for (Event event : Event.values())
                for (Role role : Role.values()) {
                    TransactionStatus to = ALLOWED.get(List.of(from, event, role));
                    Transition expected = to != null ? new Transition.AllowedTo(to)
                            : role != ONLY.get(event) ? new Transition.WrongActor()   // a 403, even on a decided item
                            : new Transition.NotPending();                             // "Transaction no longer pending"
                    all.add(Arguments.of(from, event, role, expected));
                }
        return all.stream();
    }

    @ParameterizedTest(name = "{0} + {1} by {2} -> {3}")
    @MethodSource("everyCombination")
    void theTableDecidesEveryCombination(TransactionStatus from, Event event, Role role, Transition expected) {
        assertThat(TransactionStateMachine.next(from, event, role)).isEqualTo(expected);
    }

    @Test
    void thereAreThirtySixCombinationsAndThreeAreAllowed() {
        List<Arguments> all = everyCombination().toList();

        assertThat(all).hasSize(36);
        assertThat(all).filteredOn(a -> a.get()[3] instanceof Transition.AllowedTo).hasSize(3);
    }

    // ---- roleOf -------------------------------------------------------------------

    static final UUID AMA = UUID.randomUUID();
    static final UUID BEN = UUID.randomUUID();
    static final UUID CLEO = UUID.randomUUID();

    static LedgerTransaction item(UUID creditor, UUID debtor, UUID initiator) {
        return new LedgerTransaction(UUID.randomUUID(), TransactionKind.LOAN, creditor, debtor, 2000,
                Currency.getInstance("USD"), LocalDate.of(2026, 10, 1), null, TransactionStatus.PENDING, null,
                initiator, Instant.now(), null, null, null, false, UUID.randomUUID());
    }

    @Test
    void theInitiatorIsInitiatorOnWhicheverSideTheyStand() {
        assertThat(TransactionStateMachine.roleOf(AMA, item(AMA, BEN, AMA))).isEqualTo(Role.INITIATOR);
        assertThat(TransactionStateMachine.roleOf(AMA, item(BEN, AMA, AMA))).isEqualTo(Role.INITIATOR);
    }

    @Test
    void theOtherPartyIsTheCounterpartyOnWhicheverSideTheyStand() {
        assertThat(TransactionStateMachine.roleOf(BEN, item(AMA, BEN, AMA))).isEqualTo(Role.COUNTERPARTY);
        assertThat(TransactionStateMachine.roleOf(BEN, item(BEN, AMA, AMA))).isEqualTo(Role.COUNTERPARTY);
    }

    @Test
    void anyoneElseIsAnOutsider() {
        assertThat(TransactionStateMachine.roleOf(CLEO, item(AMA, BEN, AMA))).isEqualTo(Role.OUTSIDER);
    }
}
