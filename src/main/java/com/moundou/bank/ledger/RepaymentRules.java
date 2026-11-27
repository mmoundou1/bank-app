package com.moundou.bank.ledger;

/**
 * Whether an outstanding record is eligible for repayment
 * Pure code: no database, no actor, per our "Conventions and Patterns". The answer is a fact about the record.
 * Called by DashboardService and RepaymentService.
 *
 * Specified by RepaymentRulesTests.
 */

public final class RepaymentRules {

    private RepaymentRules() {}

    public static LoanAction check(boolean viewerIsBorrower, boolean lenderActive, boolean repaymentWaiting) {

        if (viewerIsBorrower) {

            if (lenderActive) {
                if (repaymentWaiting) {
                    return LoanAction.REPAYMENT_WAITING;
                }
                else {
                    return LoanAction.REPAY;
                }
            } else {
                return LoanAction.LENDER_DEACTIVATED;
            }
        } else {
            return LoanAction.NONE;
        }

    }

}
