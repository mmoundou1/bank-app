package com.moundou.bank.ledger;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What a member submitted from the entry form (Ledger.Entry-1), exactly as typed.
 * {@link EntryValidator} turns it into a {@link NewTransaction} or a list of field errors.
 *
 * @param counterpartyId  the other member; required
 * @param role            the initiator's side: LENDER or BORROWER. Ignored in split mode,
 *                        where the initiator paid the bill and is always the lender.
 * @param amountText      the amount as typed. In split mode, the total bill.
 * @param currencyCode    USD, XAF or EUR (Ledger.Entry-13)
 * @param date            the transaction date; null means "today" in the initiator's zone
 * @param note            optional free text
 * @param split           split-the-bill mode (Ledger.Entry-7)
 * @param openingBalance  a debt from before launch, only during onboarding (Ledger.Entry-15)
 * @param submissionKey   generated when the form was rendered; the same key on a retry
 *                        (Technical Design D1, Ledger.Entry-14)
 */
public record EntryRequest(
        UUID counterpartyId,
        Role role,
        String amountText,
        String currencyCode,
        LocalDate date,
        String note,
        boolean split,
        boolean openingBalance,
        UUID submissionKey) {

    /** The initiator's side of a loan. There is deliberately no interest field (Ledger.Entry-10). */
    public enum Role { LENDER, BORROWER }
}
