package com.moundou.bank.ledger;

import com.moundou.bank.identity.Member;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every rule for recording a new entry, as pure code (SRS 3.1, Ledger.Entry-1 to -16).
 * No database and no Spring, so the whole rule set runs in the fast suite.
 *
 * All errors are collected, not just the first, and each is keyed by the form field it
 * belongs to, so the form can show every problem inline beside its field and keep what
 * the member typed (UI-4). Every error is a message key in messages.properties (I18N-1).
 */
public final class EntryValidator {

    public static final Set<String> CURRENCIES = Set.of("USD", "XAF", "EUR");   // Ledger.Entry-13

    // Form fields, as the form will name them.
    public static final String COUNTERPARTY = "counterparty";
    public static final String ROLE = "role";
    public static final String AMOUNT = "amount";
    public static final String CURRENCY = "currency";
    public static final String DATE = "date";
    public static final String OPENING_BALANCE = "openingBalance";

    // Message keys. The three with SRS-mandated wording are checked by SrsMessageTests.
    static final String COUNTERPARTY_REQUIRED = "ledger.entry.counterparty.required";
    static final String COUNTERPARTY_SELF = "ledger.entry.counterparty.self";                // Ledger.Entry-3
    static final String COUNTERPARTY_UNAVAILABLE = "ledger.entry.counterparty.unavailable";  // Auth.Roles-4
    static final String ROLE_REQUIRED = "ledger.entry.role.required";
    static final String CURRENCY_UNSUPPORTED = "ledger.entry.currency.unsupported";          // Ledger.Entry-13
    static final String DATE_FUTURE = "ledger.entry.date.future";                            // Ledger.Entry-8
    static final String OPENING_BALANCE_CLOSED = "ledger.entry.openingBalance.closed";        // Ledger.Entry-16

    /** The largest amount the schema holds: amount_minor is a Postgres integer (MB-7). */
    static final long MAX_MINOR_UNITS = Integer.MAX_VALUE;

    private final Clock clock;
    private final LocalDate onboardingEndsOn;

    /**
     * @param onboardingEndsOn last day, inclusive, on which opening balances are accepted
     *                         (Ledger.Entry-16), judged in the initiator's own zone.
     *                         Null means the window is closed.
     */
    public EntryValidator(Clock clock, LocalDate onboardingEndsOn) {
        this.clock = clock;
        this.onboardingEndsOn = onboardingEndsOn;
    }

    public sealed interface Result {
        record Valid(NewTransaction transaction) implements Result { }
        record Invalid(Map<String, String> fieldErrors) implements Result { }
    }

    /** "Today" for this member: the current date in their configured zone (CON-8, Ledger.Entry-12). */
    public LocalDate today(Member member) {
        return LocalDate.now(clock.withZone(member.timeZone()));
    }

    /**
     * Split the bill (Ledger.Entry-7, -11): the other member owes half, and an odd minor
     * unit goes to the member who paid. So $25.01 becomes $12.51.
     */
    public static long splitObligation(long totalMinorUnits) {
        return totalMinorUnits / 2 + totalMinorUnits % 2;
    }

    /**
     * @param initiator    the signed-in member making the entry
     * @param counterparty the member named as counterparty, if one exists with that id
     */
    public Result validate(Member initiator, Optional<Member> counterparty, EntryRequest request, Locale locale) {
        if (request.submissionKey() == null) {
            // The form always sends one. Its absence is a bug, not something a member can fix.
            throw new IllegalArgumentException("submissionKey is required (Technical Design D1)");
        }
        Map<String, String> errors = new LinkedHashMap<>();

        // Counterparty (Ledger.Entry-3, Auth.Roles-4).
        if (request.counterpartyId() == null) {
            errors.put(COUNTERPARTY, COUNTERPARTY_REQUIRED);
        } else if (request.counterpartyId().equals(initiator.id())) {
            errors.put(COUNTERPARTY, COUNTERPARTY_SELF);
        } else if (counterparty.isEmpty() || !counterparty.get().active()) {
            errors.put(COUNTERPARTY, COUNTERPARTY_UNAVAILABLE);
        }

        // Role. Split mode needs none: the initiator paid, so is the lender.
        EntryRequest.Role role = request.split() ? EntryRequest.Role.LENDER : request.role();
        if (role == null) {
            errors.put(ROLE, ROLE_REQUIRED);
        }

        // Currency, then the amount in that currency's minor unit (Ledger.Entry-2, -13).
        Currency currency = null;
        if (request.currencyCode() == null || !CURRENCIES.contains(request.currencyCode())) {
            errors.put(CURRENCY, CURRENCY_UNSUPPORTED);
        } else {
            currency = Currency.getInstance(request.currencyCode());
        }
        long amountMinor = 0;
        if (currency != null) {
            switch (Amounts.parse(request.amountText(), currency, locale)) {
                case Amounts.Parsed.Invalid invalid -> errors.put(AMOUNT, invalid.messageKey());
                case Amounts.Parsed.Ok ok -> {
                    amountMinor = request.split() ? splitObligation(ok.minorUnits()) : ok.minorUnits();
                    if (amountMinor > MAX_MINOR_UNITS) {
                        errors.put(AMOUNT, Amounts.TOO_LARGE);
                    }
                }
            }
        }

        // Date: defaults to today, earlier accepted, later rejected, all in the
        // initiator's zone, never the server's or the device's (Ledger.Entry-8, -12).
        LocalDate today = today(initiator);
        LocalDate date = request.date() == null ? today : request.date();
        if (date.isAfter(today)) {
            errors.put(DATE, DATE_FUTURE);
        }

        // Opening balances only during onboarding (Ledger.Entry-15, -16).
        if (request.openingBalance() && (onboardingEndsOn == null || today.isAfter(onboardingEndsOn))) {
            errors.put(OPENING_BALANCE, OPENING_BALANCE_CLOSED);
        }

        if (!errors.isEmpty()) {
            return new Result.Invalid(Collections.unmodifiableMap(errors));
        }

        // Direction (SRS 4.2): the creditor gains a claim on the debtor.
        UUID other = request.counterpartyId();
        boolean initiatorLends = role == EntryRequest.Role.LENDER;
        return new Result.Valid(new NewTransaction(
                UUID.randomUUID(),                    // application-generated key (ADR-015)
                TransactionKind.LOAN,                 // splits and opening balances are ordinary loans (T-Entry-7b)
                initiatorLends ? initiator.id() : other,
                initiatorLends ? other : initiator.id(),
                amountMinor,
                currency,
                date,
                request.note() == null || request.note().isBlank() ? null : request.note().strip(),
                initiator.id(),                       // Ledger.Entry-9
                null,
                null,
                request.openingBalance(),
                request.submissionKey()));
    }
}
