package com.moundou.bank.ledger;

import com.moundou.bank.identity.Member;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast suite. The entry rules of SRS 3.1, without a database. Test IDs are from the
 * Test Derivation page.
 */
class EntryValidatorTests {

    private static final ZoneId DOUALA = ZoneId.of("Africa/Douala");      // UTC+1
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York"); // UTC-4 in September

    private static final Member AMA = new Member(UUID.randomUUID(), "Ama", DOUALA, true);
    private static final Member BEN = new Member(UUID.randomUUID(), "Ben", NEW_YORK, true);
    private static final Member GONE = new Member(UUID.randomUUID(), "Gone", DOUALA, false);

    /** 2026-09-27 23:30 UTC: already the 28th in Douala, still the 27th in New York. */
    private static final Instant LATE_ON_THE_27TH_UTC = Instant.parse("2026-09-27T23:30:00Z");

    private static EntryValidator validator(Instant now, LocalDate onboardingEndsOn) {
        return new EntryValidator(Clock.fixed(now, ZoneOffset.UTC), onboardingEndsOn);
    }

    private static EntryValidator validator() {
        return validator(LATE_ON_THE_27TH_UTC, null);
    }

    private static EntryRequest request(Member counterparty, EntryRequest.Role role, String amount, String currency) {
        return new EntryRequest(counterparty.id(), role, amount, currency, null, null, false, false, UUID.randomUUID());
    }

    private static EntryValidator.Result validate(EntryValidator v, Member initiator, Member counterparty, EntryRequest r) {
        return v.validate(initiator, Optional.ofNullable(counterparty), r, Locale.ENGLISH);
    }

    private static NewTransaction valid(EntryValidator.Result result) {
        assertThat(result).isInstanceOf(EntryValidator.Result.Valid.class);
        return ((EntryValidator.Result.Valid) result).transaction();
    }

    private static Map<String, String> errors(EntryValidator.Result result) {
        assertThat(result).isInstanceOf(EntryValidator.Result.Invalid.class);
        return ((EntryValidator.Result.Invalid) result).fieldErrors();
    }

    // ---- Direction and the stored record -----------------------------------------------

    @Test
    void aLenderIsTheCreditor() {
        NewTransaction t = valid(validate(validator(), AMA, BEN, request(BEN, EntryRequest.Role.LENDER, "20.00", "USD")));
        assertThat(t.creditor()).isEqualTo(AMA.id());
        assertThat(t.debtor()).isEqualTo(BEN.id());
        assertThat(t.initiatedBy()).isEqualTo(AMA.id());    // T-Entry-9a
        assertThat(t.kind()).isEqualTo(TransactionKind.LOAN);
        assertThat(t.amountMinor()).isEqualTo(2000);
        assertThat(t.currency()).isEqualTo(Currency.getInstance("USD"));
    }

    @Test
    void aBorrowerIsTheDebtor() {
        NewTransaction t = valid(validate(validator(), AMA, BEN, request(BEN, EntryRequest.Role.BORROWER, "20.00", "USD")));
        assertThat(t.creditor()).isEqualTo(BEN.id());
        assertThat(t.debtor()).isEqualTo(AMA.id());
        assertThat(t.initiatedBy()).isEqualTo(AMA.id());
    }

    @Test
    void theSubmissionKeyIsCarriedThrough() {   // Ledger.Entry-14
        EntryRequest r = request(BEN, EntryRequest.Role.LENDER, "1", "USD");
        assertThat(valid(validate(validator(), AMA, BEN, r)).submissionKey()).isEqualTo(r.submissionKey());
    }

    @Test
    void aMissingSubmissionKeyIsABugNotAFieldError() {
        EntryRequest r = new EntryRequest(BEN.id(), EntryRequest.Role.LENDER, "1", "USD", null, null, false, false, null);
        assertThatThrownBy(() -> validate(validator(), AMA, BEN, r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankNoteIsStoredAsNoNote() {
        EntryRequest r = new EntryRequest(BEN.id(), EntryRequest.Role.LENDER, "1", "USD", null, "   ", false, false, UUID.randomUUID());
        assertThat(valid(validate(validator(), AMA, BEN, r)).note()).isNull();
    }

    // ---- Counterparty (Ledger.Entry-3, Auth.Roles-4) -----------------------------------

    @Test
    void youCannotBeYourOwnCounterparty() {   // T-Entry-3a
        assertThat(errors(validate(validator(), AMA, AMA, request(AMA, EntryRequest.Role.LENDER, "5", "USD"))))
                .containsEntry("counterparty", "ledger.entry.counterparty.self");
    }

    @Test
    void anUnknownOrDeactivatedCounterpartyIsRefused() {
        assertThat(errors(validate(validator(), AMA, null, request(BEN, EntryRequest.Role.LENDER, "5", "USD"))))
                .containsEntry("counterparty", "ledger.entry.counterparty.unavailable");
        assertThat(errors(validate(validator(), AMA, GONE, request(GONE, EntryRequest.Role.LENDER, "5", "USD"))))
                .containsEntry("counterparty", "ledger.entry.counterparty.unavailable");
    }

    // ---- Amount and currency (Ledger.Entry-2, -13) -------------------------------------

    @Test
    void everyProblemIsReportedAtOnceAgainstItsField() {   // UI-4
        EntryRequest r = new EntryRequest(AMA.id(), null, "0", "GBP", LocalDate.of(2030, 1, 1), null, false, true, UUID.randomUUID());
        assertThat(errors(validate(validator(), AMA, AMA, r))).containsOnlyKeys(
                "counterparty", "role", "currency", "date", "openingBalance");
        EntryRequest amount = request(BEN, EntryRequest.Role.LENDER, "0", "USD");
        assertThat(errors(validate(validator(), AMA, BEN, amount)))
                .containsExactly(Map.entry("amount", "ledger.entry.amount.notPositive"));   // T-Entry-2a
    }

    @Test
    void theAmountIsReadInTheChosenCurrency() {   // T-Entry-13a to -13c
        assertThat(valid(validate(validator(), AMA, BEN, request(BEN, EntryRequest.Role.LENDER, "10,000", "XAF"))).amountMinor())
                .isEqualTo(10_000);
        assertThat(errors(validate(validator(), AMA, BEN, request(BEN, EntryRequest.Role.LENDER, "10,000.50", "XAF"))))
                .containsEntry("amount", "ledger.entry.amount.tooPrecise");
        assertThat(errors(validate(validator(), AMA, BEN, request(BEN, EntryRequest.Role.LENDER, "12.345", "USD"))))
                .containsEntry("amount", "ledger.entry.amount.tooPrecise");
    }

    @Test
    void amountsBeyondWhatTheSchemaHoldsAreRefused() {
        assertThat(valid(validate(validator(), AMA, BEN, request(BEN, EntryRequest.Role.LENDER, "2147483647", "XAF"))).amountMinor())
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(errors(validate(validator(), AMA, BEN, request(BEN, EntryRequest.Role.LENDER, "2147483648", "XAF"))))
                .containsEntry("amount", "ledger.entry.amount.tooLarge");
    }

    // ---- Split the bill (Ledger.Entry-7, -11) ------------------------------------------

    @Test
    void splitRecordsHalfAsAnOrdinaryLoanToThePayer() {   // T-Entry-7a, -7b
        EntryRequest r = new EntryRequest(BEN.id(), EntryRequest.Role.BORROWER, "40.00", "USD", null, null, true, false, UUID.randomUUID());
        NewTransaction t = valid(validate(validator(), AMA, BEN, r));
        assertThat(t.amountMinor()).isEqualTo(2000);
        assertThat(t.kind()).isEqualTo(TransactionKind.LOAN);
        assertThat(t.creditor()).as("the member who paid is owed, whatever role was sent").isEqualTo(AMA.id());
    }

    @Test
    void anOddMinorUnitGoesToThePayer() {   // T-Entry-11a
        assertThat(EntryValidator.splitObligation(2501)).isEqualTo(1251);
        assertThat(EntryValidator.splitObligation(2500)).isEqualTo(1250);
        assertThat(EntryValidator.splitObligation(1)).isEqualTo(1);
        assertThat(EntryValidator.splitObligation(10_001)).as("XAF, whole francs").isEqualTo(5_001);
    }

    // ---- Dates in the member's own zone (Ledger.Entry-8, -12, CON-8) ------------------

    @Test
    void theDateDefaultsToTodayInTheInitiatorsZone() {   // T-Entry-8a, -12b
        EntryValidator v = validator(Instant.parse("2026-09-28T01:00:00Z"), null);
        assertThat(v.today(AMA)).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(v.today(BEN)).as("New York is still on the previous day").isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(valid(validate(v, BEN, AMA, request(AMA, EntryRequest.Role.LENDER, "1", "USD"))).transactionDate())
                .isEqualTo(LocalDate.of(2026, 9, 27));
    }

    @Test
    void theMembersZoneDecidesWhatIsInTheFuture() {   // T-Entry-12a
        // 23:30 UTC on the 27th is 00:30 on the 28th in Douala. Judged in UTC, the 28th
        // would be tomorrow and wrongly rejected.
        LocalDate the28th = LocalDate.of(2026, 9, 28);
        EntryRequest dated28 = new EntryRequest(BEN.id(), EntryRequest.Role.LENDER, "1", "USD", the28th, null, false, false, UUID.randomUUID());
        assertThat(valid(validate(validator(), AMA, BEN, dated28)).transactionDate()).isEqualTo(the28th);

        // The same date is still tomorrow for a member in New York.
        EntryRequest fromBen = new EntryRequest(AMA.id(), EntryRequest.Role.LENDER, "1", "USD", the28th, null, false, false, UUID.randomUUID());
        assertThat(errors(validate(validator(), BEN, AMA, fromBen))).containsEntry("date", "ledger.entry.date.future");
    }

    @Test
    void earlierDatesAreAcceptedAndLaterOnesRejected() {   // T-Entry-8b, -8c
        LocalDate today = validator().today(AMA);
        EntryRequest yesterday = new EntryRequest(BEN.id(), EntryRequest.Role.LENDER, "1", "USD", today.minusDays(1), null, false, false, UUID.randomUUID());
        EntryRequest tomorrow = new EntryRequest(BEN.id(), EntryRequest.Role.LENDER, "1", "USD", today.plusDays(1), null, false, false, UUID.randomUUID());
        assertThat(valid(validate(validator(), AMA, BEN, yesterday)).transactionDate()).isEqualTo(today.minusDays(1));
        assertThat(errors(validate(validator(), AMA, BEN, tomorrow))).containsEntry("date", "ledger.entry.date.future");
    }

    // ---- Opening balances (Ledger.Entry-15, -16) --------------------------------------

    private static EntryRequest openingBalance(LocalDate date) {
        return new EntryRequest(BEN.id(), EntryRequest.Role.LENDER, "150.00", "USD", date, "from 2022", false, true, UUID.randomUUID());
    }

    @Test
    void anOpeningBalanceIsAnOrdinaryLoanWithItsOriginalDate() {   // T-Entry-15a
        NewTransaction t = valid(validate(validator(LATE_ON_THE_27TH_UTC, LocalDate.of(2026, 10, 31)), AMA, BEN,
                openingBalance(LocalDate.of(2022, 3, 14))));
        assertThat(t.openingBalance()).isTrue();
        assertThat(t.kind()).isEqualTo(TransactionKind.LOAN);
        assertThat(t.transactionDate()).isEqualTo(LocalDate.of(2022, 3, 14));
    }

    @Test
    void theOnboardingWindowIncludesItsLastDayInTheMembersZone() {   // T-Entry-16a
        LocalDate endsOn = LocalDate.of(2026, 9, 27);
        // 23:30 UTC on the 27th: still the 27th in New York, already the 28th in Douala.
        EntryValidator v = validator(LATE_ON_THE_27TH_UTC, endsOn);
        EntryRequest fromBen = new EntryRequest(AMA.id(), EntryRequest.Role.LENDER, "150.00", "USD", LocalDate.of(2022, 3, 14), null, false, true, UUID.randomUUID());
        assertThat(valid(validate(v, BEN, AMA, fromBen)).openingBalance()).isTrue();
        assertThat(errors(validate(v, AMA, BEN, openingBalance(LocalDate.of(2022, 3, 14)))))
                .containsExactly(Map.entry("openingBalance", "ledger.entry.openingBalance.closed"));
    }

    @Test
    void noConfiguredWindowMeansClosed() {
        assertThat(errors(validate(validator(LATE_ON_THE_27TH_UTC, null), AMA, BEN, openingBalance(LocalDate.of(2022, 3, 14)))))
                .containsEntry("openingBalance", "ledger.entry.openingBalance.closed");
    }
}
