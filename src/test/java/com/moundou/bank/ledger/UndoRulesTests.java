package com.moundou.bank.ledger;

import com.moundou.bank.ledger.UndoRules.Check;
import com.moundou.bank.ledger.UndoRules.Check.Refused;
import com.moundou.bank.ledger.UndoRules.Check.Undoable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static com.moundou.bank.ledger.TransactionStatus.APPROVED;
import static com.moundou.bank.ledger.TransactionStatus.CANCELLED;
import static com.moundou.bank.ledger.TransactionStatus.DECLINED;
import static com.moundou.bank.ledger.TransactionStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. Whether a record can be undone (Ledger.History-9, -11; INT-6), as pure
 * code. This is the specification for UndoRules.check: when every test passes, the
 * rule is right.
 *
 * Ama lends to Ben throughout. Rows are built by hand; nothing touches a database.
 */
class UndoRulesTests {

    static final UUID AMA = UUID.randomUUID();
    static final UUID BEN = UUID.randomUUID();
    static final Currency USD = Currency.getInstance("USD");

    static final String IS_A_CORRECTION = "ledger.history.undo.isCorrection";
    static final String NOT_APPROVED = "ledger.history.undo.notApproved";
    static final String ALREADY_UNDONE = "ledger.history.correction.alreadyCorrected";       // INT-6
    static final String REPAYMENT_FIRST = "ledger.history.correction.conflictsWithRepayment"; // History-11

    // ---- the four questions, one at a time -------------------------------------------

    @Test
    void anApprovedLoanWithNothingAgainstItCanBeUndone() {                     // T-Hist-5a
        assertThat(check(loan(APPROVED))).isEqualTo(new Undoable());
    }

    @Test
    void anApprovedRepaymentCanBeUndone() {                                    // L2: how a repaid loan is freed
        LedgerTransaction loan = loan(APPROVED);
        assertThat(check(repaymentOf(loan, APPROVED), loan)).isEqualTo(new Undoable());
    }

    @Nested
    class Question1_IsItACorrection {

        @Test
        void anApprovedCorrectionCannotBeUndone() {                            // T-Hist-9c, CR-17
            assertThat(check(correctionOf(loan(APPROVED), APPROVED))).isEqualTo(new Refused(IS_A_CORRECTION));
        }

        @Test
        void aCorrectionIsRefusedAsACorrectionWhateverItsStatus() {
            // Order: the permanent fact (kind) is asked before the changeable one (status),
            // so a pending correction is not told "not approved yet", as if it might become
            // undoable later.
            for (TransactionStatus status : List.of(PENDING, DECLINED, CANCELLED)) {
                assertThat(check(correctionOf(loan(APPROVED), status)))
                        .as(status.name()).isEqualTo(new Refused(IS_A_CORRECTION));
            }
        }
    }

    @Nested
    class Question2_IsItApproved {

        @Test
        void aLoanThatNeverCountedCannotBeUndone() {
            for (TransactionStatus status : List.of(PENDING, DECLINED, CANCELLED)) {
                assertThat(check(loan(status))).as(status.name()).isEqualTo(new Refused(NOT_APPROVED));
            }
        }

        @Test
        void aRepaymentThatNeverCountedCannotBeUndone() {
            LedgerTransaction loan = loan(APPROVED);
            for (TransactionStatus status : List.of(PENDING, DECLINED, CANCELLED)) {
                assertThat(check(repaymentOf(loan, status), loan)).as(status.name()).isEqualTo(new Refused(NOT_APPROVED));
            }
        }
    }

    @Nested
    class Question3_IsItAlreadyUndone {

        @Test
        void aLoanWithAnApprovedCorrectionIsAlreadyUndone() {                   // INT-6, T-Hist-11c
            LedgerTransaction loan = loan(APPROVED);
            assertThat(check(loan, correctionOf(loan, APPROVED))).isEqualTo(new Refused(ALREADY_UNDONE));
        }

        @Test
        void aRepaymentWithAnApprovedCorrectionIsAlreadyUndone() {
            LedgerTransaction loan = loan(APPROVED);
            LedgerTransaction repayment = repaymentOf(loan, APPROVED);
            assertThat(check(repayment, loan, correctionOf(repayment, APPROVED))).isEqualTo(new Refused(ALREADY_UNDONE));
        }

        @Test
        void aCorrectionThatDidNotCountDoesNotUndoAnything() {
            // A pending correction only hides the history's link (decision 3); it is not a
            // rule, so check() does not refuse. Declined and cancelled ones never counted.
            LedgerTransaction loan = loan(APPROVED);
            for (TransactionStatus status : List.of(PENDING, DECLINED, CANCELLED)) {
                assertThat(check(loan, correctionOf(loan, status))).as(status.name()).isEqualTo(new Undoable());
            }
        }
    }

    @Nested
    class Question4_IsItASettledLoan {

        @Test
        void aLoanWithAnApprovedRepaymentMustHaveItsRepaymentUndoneFirst() {    // T-Hist-11a
            LedgerTransaction loan = loan(APPROVED);
            assertThat(check(loan, repaymentOf(loan, APPROVED))).isEqualTo(new Refused(REPAYMENT_FIRST));
        }

        @Test
        void aRepaymentThatDidNotCountDoesNotSettleTheLoan() {
            LedgerTransaction loan = loan(APPROVED);
            for (TransactionStatus status : List.of(PENDING, DECLINED, CANCELLED)) {
                assertThat(check(loan, repaymentOf(loan, status))).as(status.name()).isEqualTo(new Undoable());
            }
        }

        @Test
        void aLoanWhoseRepaymentWasUndoneCanBeUndone() {                        // T-Hist-11b, the subtle one
            LedgerTransaction loan = loan(APPROVED);
            LedgerTransaction repayment = repaymentOf(loan, APPROVED);
            assertThat(check(loan, repayment, correctionOf(repayment, APPROVED))).isEqualTo(new Undoable());
        }

        @Test
        void aRepaymentWhoseUndoIsOnlyPendingStillSettlesTheLoan() {
            LedgerTransaction loan = loan(APPROVED);
            LedgerTransaction repayment = repaymentOf(loan, APPROVED);
            assertThat(check(loan, repayment, correctionOf(repayment, PENDING))).isEqualTo(new Refused(REPAYMENT_FIRST));
        }

        @Test
        void aLoanRepaidAgainAfterAnUndoneRepaymentIsSettledAgain() {           // L4
            LedgerTransaction loan = loan(APPROVED);
            LedgerTransaction first = repaymentOf(loan, APPROVED);
            LedgerTransaction second = repaymentOf(loan, APPROVED);
            assertThat(check(loan, first, correctionOf(first, APPROVED), second)).isEqualTo(new Refused(REPAYMENT_FIRST));
        }
    }

    // ---- the order of the questions, where a row matches more than one ----------------

    @Test
    void alreadyUndoneIsAskedBeforeSettled() {
        // Not reachable through the services (INT-5), but the order should not depend on
        // that: a record that is already undone is told so, whatever else is true.
        LedgerTransaction loan = loan(APPROVED);
        assertThat(check(loan, correctionOf(loan, APPROVED), repaymentOf(loan, APPROVED)))
                .isEqualTo(new Refused(ALREADY_UNDONE));
    }

    // ---- what counts as related -------------------------------------------------------

    @Test
    void rowsThatPointAtOtherRecordsAreIgnored() {
        // So the history can pass its whole list instead of picking out each row's relatives.
        LedgerTransaction loan = loan(APPROVED);
        LedgerTransaction other = loan(APPROVED);
        assertThat(check(loan, other, correctionOf(other, APPROVED), repaymentOf(other, APPROVED)))
                .isEqualTo(new Undoable());
    }

    @Test
    void theTargetItselfInTheListChangesNothing() {
        // The history's list contains the row being asked about as well.
        LedgerTransaction loan = loan(APPROVED);
        assertThat(check(loan, loan)).isEqualTo(new Undoable());
    }

    // ---- a whole lifecycle, L1 to L4 ---------------------------------------------------

    @Test
    void theLoanLifecycleFromTheContextDoc() {
        List<LedgerTransaction> rows = new ArrayList<>();
        LedgerTransaction loan = loan(APPROVED);
        rows.add(loan);
        assertThat(UndoRules.check(loan, rows)).as("Outstanding").isEqualTo(new Undoable());

        LedgerTransaction repayment = repaymentOf(loan, APPROVED);                  // L1: repaid
        rows.add(repayment);
        assertThat(UndoRules.check(loan, rows)).as("Settled").isEqualTo(new Refused(REPAYMENT_FIRST));
        assertThat(UndoRules.check(repayment, rows)).as("its repayment").isEqualTo(new Undoable());

        LedgerTransaction undoRepayment = correctionOf(repayment, APPROVED);        // L2: repayment undone
        rows.add(undoRepayment);
        assertThat(UndoRules.check(repayment, rows)).as("repayment, undone").isEqualTo(new Refused(ALREADY_UNDONE));
        assertThat(UndoRules.check(undoRepayment, rows)).as("the correction").isEqualTo(new Refused(IS_A_CORRECTION));
        assertThat(UndoRules.check(loan, rows)).as("Outstanding again").isEqualTo(new Undoable());

        LedgerTransaction undoLoan = correctionOf(loan, APPROVED);                  // L3: loan undone
        rows.add(undoLoan);
        assertThat(UndoRules.check(loan, rows)).as("Reversed, final").isEqualTo(new Refused(ALREADY_UNDONE));
    }

    // ---- helpers ------------------------------------------------------------------------

    private static Check check(LedgerTransaction target, LedgerTransaction... related) {
        return UndoRules.check(target, List.of(related));
    }

    /** Ama lent Ben $20.00. */
    static LedgerTransaction loan(TransactionStatus status) {
        return row(TransactionKind.LOAN, AMA, BEN, status, null, null);
    }

    /** Ben pays it back: the borrower is the creditor (D2). */
    static LedgerTransaction repaymentOf(LedgerTransaction loan, TransactionStatus status) {
        return row(TransactionKind.REPAYMENT, loan.debtor(), loan.creditor(), status, loan.id(), null);
    }

    /** Undoes {@code target}: its parties swapped. */
    static LedgerTransaction correctionOf(LedgerTransaction target, TransactionStatus status) {
        return row(TransactionKind.COMPENSATING, target.debtor(), target.creditor(), status, null, target.id());
    }

    private static LedgerTransaction row(TransactionKind kind, UUID creditor, UUID debtor, TransactionStatus status,
                                         UUID settles, UUID corrects) {
        return new LedgerTransaction(UUID.randomUUID(), kind, creditor, debtor, 2000, USD, LocalDate.of(2026, 9, 3),
                null, status, status == DECLINED ? "not mine" : null, creditor, Instant.now(),
                status == PENDING ? null : Instant.now(), settles, corrects, false, UUID.randomUUID());
    }
}
