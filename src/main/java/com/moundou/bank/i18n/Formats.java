package com.moundou.bank.i18n;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;

/**
 * The one place amounts and dates become text (ADR-014, I18N-3). Templates call it as
 * {@code ${@formats.amount(...)}} and never format numbers or dates themselves, so a
 * French locale in Release 2.0 changes every figure on every screen from here.
 *
 * Amounts arrive as integer minor units and are only turned into a decimal for display;
 * the money path itself stays integer (CON-5). Precision follows the currency: two
 * digits for USD and EUR, none for XAF (ADR-013).
 */
@Component("formats")
public class Formats {

    public String amount(long minorUnits, Currency currency) {
        return amount(minorUnits, currency, LocaleContextHolder.getLocale());
    }

    public String amount(long minorUnits, Currency currency, Locale locale) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(currency);
        int digits = currency.getDefaultFractionDigits();
        format.setMinimumFractionDigits(digits);
        format.setMaximumFractionDigits(digits);
        return format.format(BigDecimal.valueOf(minorUnits).movePointLeft(digits));
    }

    /** A calendar date, never shifted by any zone (ADR-012). */
    public String date(LocalDate date) {
        return date(date, LocaleContextHolder.getLocale());
    }

    public String date(LocalDate date, Locale locale) {
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
    }

    /** A moment, shown as the date in the viewer's own zone (SRS 1.2). */
    public String dateIn(Instant instant, ZoneId zone) {
        return date(instant.atZone(zone).toLocalDate());
    }
}
