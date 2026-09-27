package com.moundou.bank.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Currency;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. Amount parsing into minor units (CON-5, Ledger.Entry-2, -13).
 */
class AmountsTests {

    private static Amounts.Parsed parse(String text, String currency) {
        return Amounts.parse(text, Currency.getInstance(currency), Locale.ENGLISH);
    }

    private static Amounts.Parsed ok(long minor) {
        return new Amounts.Parsed.Ok(minor);
    }

    private static Amounts.Parsed invalid(String key) {
        return new Amounts.Parsed.Invalid(key);
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "0.01      | USD | 1",          // T-Entry-2c: the smallest valid amount
            "12.5      | USD | 1250",
            "12.50     | USD | 1250",
            "12        | USD | 1200",
            "1,234.56  | USD | 123456",
            "0012.00   | EUR | 1200",
            "10000     | XAF | 10000",      // T-Entry-13a
            "10,000    | XAF | 10000",
            "10,000.00 | XAF | 10000",      // whole francs written with zero decimals are still whole francs
            "  7.25    | EUR | 725",
    })
    void acceptsWellFormedAmounts(String text, String currency, long minor) {
        assertThat(parse(text, currency)).isEqualTo(ok(minor));
    }

    @Test
    void zeroAndNegativeGetTheSrsMessage() {   // T-Entry-2a, -2b
        assertThat(parse("0.00", "USD")).isEqualTo(invalid("ledger.entry.amount.notPositive"));
        assertThat(parse("0", "XAF")).isEqualTo(invalid("ledger.entry.amount.notPositive"));
        assertThat(parse("-5.00", "USD")).isEqualTo(invalid("ledger.entry.amount.notPositive"));
        assertThat(parse("−5.00", "USD")).isEqualTo(invalid("ledger.entry.amount.notPositive"));
    }

    @Test
    void anythingFinerThanTheCurrencyAllowsIsRejected() {   // T-Entry-13b, -13c
        assertThat(parse("10,000.50", "XAF")).isEqualTo(invalid(Amounts.TOO_PRECISE));
        assertThat(parse("10000.5", "XAF")).isEqualTo(invalid(Amounts.TOO_PRECISE));
        assertThat(parse("12.345", "USD")).isEqualTo(invalid(Amounts.TOO_PRECISE));
        assertThat(parse("12.001", "EUR")).isEqualTo(invalid(Amounts.TOO_PRECISE));
        assertThat(parse("12.340", "USD")).isEqualTo(ok(1234));   // a trailing zero adds no precision
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"abc", "12..5", "1.2.3", "12,34", "1,2345", "$12", "12 USD", "1e3", "."})
    void malformedTextIsNotANumber(String text) {
        assertThat(parse(text, "USD")).isEqualTo(invalid(Amounts.NOT_A_NUMBER));
    }

    @Test
    void blankIsRequired() {
        assertThat(parse("", "USD")).isEqualTo(invalid(Amounts.REQUIRED));
        assertThat(parse("   ", "USD")).isEqualTo(invalid(Amounts.REQUIRED));
        assertThat(parse(null, "USD")).isEqualTo(invalid(Amounts.REQUIRED));
    }

    @Test
    void absurdlyLongNumbersAreTooLargeRatherThanOverflowing() {
        assertThat(parse("9999999999999", "USD")).isEqualTo(invalid(Amounts.TOO_LARGE));
    }

    /** I18N-2: a French locale reads French input with no code change. */
    @Test
    void separatorsComeFromTheLocale() {
        assertThat(Amounts.parse("1 234,56", Currency.getInstance("EUR"), Locale.FRENCH)).isEqualTo(ok(123456));
        assertThat(Amounts.parse("1 234,56", Currency.getInstance("EUR"), Locale.FRENCH)).isEqualTo(ok(123456));
        assertThat(Amounts.parse("12,345", Currency.getInstance("EUR"), Locale.FRENCH))
                .isEqualTo(invalid(Amounts.TOO_PRECISE));
    }
}
