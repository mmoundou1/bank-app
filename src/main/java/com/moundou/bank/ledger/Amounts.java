package com.moundou.bank.ledger;

import java.text.DecimalFormatSymbols;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the amount a member typed into integer minor units of a currency
 * (CON-5, INT-1, ADR-013, Ledger.Entry-13).
 *
 * <h2>Why the text is parsed by hand</h2>
 * The digits are read straight into a {@code long}. Nothing passes through
 * {@code double}, {@code float} or {@code BigDecimal}, so no amount is ever
 * approximated, and the precision rule becomes a plain question about digits: an
 * amount is finer than the currency allows when a non-zero digit appears after the
 * currency's last decimal place. So 10,000.50 XAF and $12.345 are rejected, while
 * 10,000.00 XAF is simply 10,000 francs.
 *
 * <h2>Why the separators come from the locale</h2>
 * The decimal and grouping characters are taken from the locale rather than written
 * in as "." and ",", so a French locale in Release 2.0 reads "1 234,56" without a
 * code change (I18N-2, I18N-3). Release 1.0 always passes English (ADR-014).
 */
public final class Amounts {

    /** Enough integer digits for any amount this ledger accepts, far below long overflow. */
    private static final int MAX_INTEGER_DIGITS = 12;

    private Amounts() { }

    /** The outcome of parsing: either minor units, or the message key of what was wrong. */
    public sealed interface Parsed {
        record Ok(long minorUnits) implements Parsed { }
        record Invalid(String messageKey) implements Parsed { }
    }

    public static final String REQUIRED = "ledger.entry.amount.required";
    public static final String NOT_A_NUMBER = "ledger.entry.amount.invalid";
    public static final String NOT_POSITIVE = "ledger.entry.amount.notPositive";   // Ledger.Entry-2 (SRS wording)
    public static final String TOO_PRECISE = "ledger.entry.amount.tooPrecise";     // Ledger.Entry-13
    public static final String TOO_LARGE = "ledger.entry.amount.tooLarge";

    public static Parsed parse(String text, Currency currency, Locale locale) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(locale, "locale");
        if (text == null || text.isBlank()) {
            return new Parsed.Invalid(REQUIRED);
        }

        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        String decimal = Pattern.quote(String.valueOf(symbols.getDecimalSeparator()));
        String grouping = groupingPattern(symbols.getGroupingSeparator());
        Pattern shape = Pattern.compile(
                "([-−])?"                                                   // an optional minus sign
                + "(\\d{1,3}(?:" + grouping + "\\d{3})+|\\d+)"                    // 1234 or 1,234
                + "(?:" + decimal + "(\\d*))?");                                  // optional fraction

        Matcher m = shape.matcher(text.strip());
        if (!m.matches()) {
            return new Parsed.Invalid(NOT_A_NUMBER);
        }
        boolean negative = m.group(1) != null;
        String whole = m.group(2).replaceAll("\\D", "");
        String fraction = m.group(3) == null ? "" : m.group(3);

        int places = currency.getDefaultFractionDigits();   // 2 for USD and EUR, 0 for XAF
        String kept = fraction.length() > places ? fraction.substring(0, places) : fraction;
        String beyond = fraction.substring(kept.length());
        if (!beyond.chars().allMatch(c -> c == '0')) {
            return new Parsed.Invalid(TOO_PRECISE);
        }

        String digits = stripLeadingZeros(whole);
        if (digits.length() > MAX_INTEGER_DIGITS) {
            return new Parsed.Invalid(TOO_LARGE);
        }
        long minor = Long.parseLong(digits);
        for (int i = 0; i < places; i++) {
            minor = minor * 10 + (i < kept.length() ? kept.charAt(i) - '0' : 0);
        }
        if (negative || minor == 0) {
            return new Parsed.Invalid(NOT_POSITIVE);   // "-5.00" gets the same message as "0" (T-Entry-2b)
        }
        return new Parsed.Ok(minor);
    }

    private static String groupingPattern(char separator) {
        // French groups with a narrow no-break space; people type an ordinary space.
        if (Character.isSpaceChar(separator)) {
            return "[   ]";
        }
        return Pattern.quote(String.valueOf(separator));
    }

    private static String stripLeadingZeros(String digits) {
        String stripped = digits.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
