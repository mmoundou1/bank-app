package com.moundou.bank.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite, no database: a web slice, which loads the locale resolver and message
 * source Spring Boot configures from application.yml, and nothing that needs Postgres.
 *
 * ADR-014 / I18N-2: Release 1.0 is English only and ignores the browser's language,
 * so a member whose phone is set to French never sees a half-translated screen and
 * a bug report reproduces the same text for everyone.
 */
@WebMvcTest
class LocaleConfigTests {

    @Autowired
    private LocaleResolver localeResolver;

    @Autowired
    private MessageSource messageSource;

    @Test
    void localeIsFixedToEnglishWhateverTheBrowserAsksFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr-FR,fr;q=0.9");

        assertThat(localeResolver).isInstanceOf(FixedLocaleResolver.class);
        assertThat(localeResolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void messagesResolveFromTheBundleEvenForAnotherLocale() {
        // No messages_fr.properties exists yet, and fallback-to-system-locale is off,
        // so a French lookup lands on the English base bundle rather than the server's locale.
        assertThat(messageSource.getMessage("ledger.approval.notPending", null, Locale.FRENCH))
                .isEqualTo("Transaction no longer pending");
    }
}
