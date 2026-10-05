package com.moundou.bank.ledger;

/**
 * The facts about one approved loan that INT-5 and Ledger.History-11 are judged on
 * (SRS 1.12, CR-15). Read by {@link TransactionRepository#loanStandingIfApproved};
 * the repository only counts, and the approval service decides.
 *
 * @param reversed              the loan is reversed by an approved compensating entry
 *                              that has not itself been reversed
 * @param unreversedRepayments  how many approved repayments of the loan have not been
 *                              reversed. INT-5 allows at most one, and none at all
 *                              while the loan is reversed.
 */
public record LoanStanding(boolean reversed, int unreversedRepayments) {
}
