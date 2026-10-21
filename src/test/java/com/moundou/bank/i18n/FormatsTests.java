package com.moundou.bank.i18n;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. T-I18N-3a: amounts and dates follow the locale, and precision follows the
 * currency, with no code change between English and French.
 */
class FormatsTests {

    private final Formats formats = new Formats();
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency XAF = Currency.getInstance("XAF");

    /** Java puts a no-break space inside French numbers; compare on ordinary spaces. */
    private static String spaces(String s) {
        return s.replace(' ', ' ').replace(' ', ' ');
    }

    @Test
    void englishAmounts() {
        assertThat(formats.amount(123456, USD, Locale.ENGLISH)).isEqualTo("$1,234.56");
        assertThat(formats.amount(123456, EUR, Locale.ENGLISH)).isEqualTo("€1,234.56");
        assertThat(formats.amount(10000, XAF, Locale.ENGLISH)).isEqualTo("FCFA10,000");
    }

    @Test
    void frenchAmountsWithTheSameCode() {
        assertThat(spaces(formats.amount(123456, EUR, Locale.FRENCH))).isEqualTo("1 234,56 €");
        assertThat(spaces(formats.amount(10000, XAF, Locale.FRENCH))).isEqualTo("10 000 FCFA");
    }

    @Test
    void francsNeverShowDecimals() {
        assertThat(formats.amount(5, XAF, Locale.ENGLISH)).doesNotContain(".");
        assertThat(formats.amount(5, XAF, Locale.FRENCH)).doesNotContain(",");
    }

    @Test
    void negativeAmountsKeepTheirSign() {
        assertThat(formats.amount(-2500, USD, Locale.ENGLISH)).isEqualTo("-$25.00");
    }

    @Test
    void datesFollowTheLocale() {
        LocalDate date = LocalDate.of(2026, 9, 28);
        assertThat(formats.date(date, Locale.ENGLISH)).isEqualTo("Sep 28, 2026");
        assertThat(formats.date(date, Locale.FRENCH)).isEqualTo("28 sept. 2026");
    }

    /** UI-6: a pre-filled amount is typed back exactly, in each currency and locale. */
    @Test
    void aPlainAmountReadsBackAsTheSameMinorUnits() {
        assertThat(formats.plainAmount(123456, USD, Locale.ENGLISH)).isEqualTo("1234.56");
        assertThat(formats.plainAmount(50000, XAF, Locale.ENGLISH)).isEqualTo("50000");
        assertThat(formats.plainAmount(5, EUR, Locale.FRENCH)).isEqualTo("0,05");
        for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.FRENCH}) {
            for (Currency currency : new Currency[] {USD, EUR, XAF}) {
                for (long minor : new long[] {1, 99, 2000, 123456, 1_000_000_000L}) {
                    String typed = formats.plainAmount(minor, currency, locale);
                    assertThat(com.moundou.bank.ledger.Amounts.parse(typed, currency, locale))
                            .as("%s %s in %s", typed, currency, locale)
                            .isEqualTo(new com.moundou.bank.ledger.Amounts.Parsed.Ok(minor));
                }
            }
        }
    }

    @Test
    void aMomentIsShownInTheViewersZoneNotInUtc() {                    // CR-2, T-Con-8a
        // 23:30 UTC on Oct 4 is still the 4th in New York, already the 5th in Douala.
        Instant decided = Instant.parse("2026-10-04T23:30:00Z");

        assertThat(spaces(formats.dateTimeIn(decided, ZoneId.of("America/New_York"), Locale.ENGLISH)))
                .isEqualTo("Oct 4, 2026, 7:30 PM");
        assertThat(spaces(formats.dateTimeIn(decided, ZoneId.of("Africa/Douala"), Locale.ENGLISH)))
                .isEqualTo("Oct 5, 2026, 12:30 AM");
    }
}
