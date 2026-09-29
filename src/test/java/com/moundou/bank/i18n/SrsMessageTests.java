package com.moundou.bank.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. The SRS specifies the exact wording of several messages. They live in
 * messages.properties (ADR-014, I18N-1), so this test is what stops the wording drifting
 * from the requirement: change the text through change control, then here.
 */
class SrsMessageTests {

    /** Message key -> exact text required by SRS 1.10 (requirement tag in the comment). */
    private static final Map<String, String> REQUIRED = Map.of(
            "ledger.entry.amount.notPositive", "Amount must be greater than zero",                                    // Ledger.Entry-2
            "ledger.entry.counterparty.self", "Counterparty cannot be yourself",                                      // Ledger.Entry-3
            "ledger.entry.openingBalance.closed", "Opening balances can only be recorded during onboarding",          // Ledger.Entry-16
            "ledger.repayment.amount.mismatch", "Repayment must match the outstanding loan amount exactly",            // Ledger.Repayment-7
            "ledger.repayment.currency.mismatch", "Repayment must be in the loan's currency",                          // Ledger.Repayment-9
            "ledger.repayment.loan.notOutstanding", "This loan is no longer outstanding",                              // Ledger.Repayment-10
            "ledger.approval.notPending", "Transaction no longer pending",                                             // Ledger.Approval-9
            "dashboard.offline", "Offline — showing last known state",                                            // Dashboard.Net-5
            "admin.lastAdministrator", "The family must always have an active administrator"                         // Auth.Roles-12
    );

    static Properties loadBundle() throws IOException {
        Properties bundle = new Properties();
        try (InputStream in = SrsMessageTests.class.getResourceAsStream("/messages.properties")) {
            assertThat(in).as("messages.properties on the classpath").isNotNull();
            bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return bundle;
    }

    @Test
    void srsMandatedMessagesMatchTheRequirementWordForWord() throws IOException {
        Properties bundle = loadBundle();
        REQUIRED.forEach((key, text) ->
                assertThat(bundle.getProperty(key)).as("message %s", key).isEqualTo(text));
    }

    @Test
    void noMessageIsBlank() throws IOException {
        Properties bundle = loadBundle();
        bundle.stringPropertyNames().forEach(key ->
                assertThat(bundle.getProperty(key)).as("message %s", key).isNotBlank());
    }
}
