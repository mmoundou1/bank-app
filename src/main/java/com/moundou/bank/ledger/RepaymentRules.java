package com.moundou.bank.ledger;

public class RepaymentRules {

    private RepaymentRules() {}

    public static LoanAction check(boolean viewerIsBorrower, boolean lenderActive, boolean repaymentWaiting) {

        if (viewerIsBorrower) {

            if (lenderActive) {
                if (repaymentWaiting)
                    return LoanAction.REPAYMENT_WAITING;
                else
                    return LoanAction.REPAY;
            } else
                return LoanAction.LENDER_DEACTIVATED;
        } else
            return LoanAction.NONE;


    }

}
