package com.moundou.bank.ledger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.moundou.bank.ledger.LoanAction.LENDER_DEACTIVATED;
import static com.moundou.bank.ledger.LoanAction.NONE;
import static com.moundou.bank.ledger.LoanAction.REPAY;
import static com.moundou.bank.ledger.LoanAction.REPAYMENT_WAITING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. What an Outstanding loan offers the member looking at it, in place of a
 * Repay link: rules L4 to L7 of the Dashboard feature page, section 7.2 (page 30572588),
 * and Ledger.Repayment-8 and -11 (CR-21). This is the specification for
 * RepaymentRules.check: when every test passes, the rule is right.
 *
 * check(viewerIsBorrower, lenderActive, repaymentWaiting) takes facts, never ids: the
 * service fetches them, so nothing here touches a database. Which loans are listed at
 * all (L1 to L3: only Outstanding ones) is the query's job, not this class's.
 *
 * The rules are asked in order and the first that matches decides:
 *   L4  you are the lender                 -> NONE
 *   L5  the lender is deactivated          -> LENDER_DEACTIVATED
 *   L6  a repayment is waiting             -> REPAYMENT_WAITING
 *   L7  otherwise                          -> REPAY
 */
class RepaymentRulesTests {

    static final boolean BORROWER = true;
    static final boolean LENDER = false;
    static final boolean ACTIVE = true;
    static final boolean DEACTIVATED = false;
    static final boolean WAITING = true;
    static final boolean NOTHING_WAITING = false;

    // ---- the ordinary case --------------------------------------------------------------

    @Test
    void aBorrowerWhoseLenderIsActiveAndWithNothingWaitingCanRepay() {              // L7, T-Repay-1a
        assertThat(RepaymentRules.check(BORROWER, ACTIVE, NOTHING_WAITING)).isEqualTo(REPAY);
    }

    // ---- one rule at a time, in the order they are asked ------------------------------

    @Nested
    class L4_YouAreTheLender {

        @Test
        void theLenderIsNeverOfferedAnything() {                                     // L4, scenario 1
            assertThat(RepaymentRules.check(LENDER, ACTIVE, NOTHING_WAITING)).isEqualTo(NONE);
        }

        @Test
        void theLenderIsNeverOfferedAnythingWhateverElseIsTrue() {                   // L4, scenario 2
            // A repayment can only be recorded by the borrower and approved by the lender,
            // so nothing about the lender's own account or a waiting repayment changes
            // what the lender sees here: the waiting repayment is in their queue instead.
            for (boolean lenderActive : new boolean[] {ACTIVE, DEACTIVATED}) {
                for (boolean repaymentWaiting : new boolean[] {WAITING, NOTHING_WAITING}) {
                    assertThat(RepaymentRules.check(LENDER, lenderActive, repaymentWaiting))
                            .as("lenderActive=%s, repaymentWaiting=%s", lenderActive, repaymentWaiting)
                            .isEqualTo(NONE);
                }
            }
        }
    }

    @Nested
    class L5_TheLenderIsDeactivated {

        @Test
        void aBorrowerCannotRepayADeactivatedLender() {                              // L5, scenario 3, T-Repay-8f
            assertThat(RepaymentRules.check(BORROWER, DEACTIVATED, NOTHING_WAITING))
                    .isEqualTo(LENDER_DEACTIVATED);
        }

        @Test
        void aDeactivatedLenderWinsOverAWaitingRepayment() {                          // L5 before L6, scenario 4
            // Order: the lasting fact (the lender can't sign in) is asked before the
            // changeable one (a repayment is pending). "Repayment waiting for Frank" would
            // leave the borrower waiting for someone who can never approve.
            assertThat(RepaymentRules.check(BORROWER, DEACTIVATED, WAITING))
                    .isEqualTo(LENDER_DEACTIVATED);
        }
    }

    @Nested
    class L6_ARepaymentIsWaiting {

        @Test
        void aBorrowerWithARepaymentWaitingIsToldSoInsteadOfOfferedRepay() {        // L6, scenario 5, T-Repay-8e
            assertThat(RepaymentRules.check(BORROWER, ACTIVE, WAITING)).isEqualTo(REPAYMENT_WAITING);
        }
    }

    // ---- every combination, so no case is left to chance ------------------------------

    @Test
    void everyCombinationHasExactlyTheOutcomeOfTheFirstMatchingRule() {
        // The same table as section 7.2, all eight combinations. If a new rule is ever
        // added, this is the test that shows which combinations it changes.
        Object[][] table = {
                // viewerIsBorrower, lenderActive, repaymentWaiting, expected
                {LENDER,   ACTIVE,      NOTHING_WAITING, NONE},
                {LENDER,   ACTIVE,      WAITING,         NONE},
                {LENDER,   DEACTIVATED, NOTHING_WAITING, NONE},
                {LENDER,   DEACTIVATED, WAITING,         NONE},
                {BORROWER, DEACTIVATED, NOTHING_WAITING, LENDER_DEACTIVATED},
                {BORROWER, DEACTIVATED, WAITING,         LENDER_DEACTIVATED},
                {BORROWER, ACTIVE,      WAITING,         REPAYMENT_WAITING},
                {BORROWER, ACTIVE,      NOTHING_WAITING, REPAY},
        };
        for (Object[] row : table) {
            assertThat(RepaymentRules.check((boolean) row[0], (boolean) row[1], (boolean) row[2]))
                    .as("borrower=%s, lenderActive=%s, repaymentWaiting=%s", row[0], row[1], row[2])
                    .isEqualTo(row[3]);
        }
    }
}
