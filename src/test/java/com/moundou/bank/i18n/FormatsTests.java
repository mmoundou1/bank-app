package com.moundou.bank.i18n;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
}
