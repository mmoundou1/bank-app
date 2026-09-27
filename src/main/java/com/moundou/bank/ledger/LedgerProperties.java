package com.moundou.bank.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * Ledger settings, bound from {@code bank.ledger.*} (Technical Design 10: all
 * configuration comes from the environment).
 *
 * @param onboardingEndsOn the last day, inclusive, on which opening balances are
 *                         accepted (Ledger.Entry-15, -16), judged in the initiating
 *                         member's own time zone. Unset means the window is closed,
 *                         which is the safe default once onboarding is over.
 */
@ConfigurationProperties("bank.ledger")
public record LedgerProperties(LocalDate onboardingEndsOn) {
}
