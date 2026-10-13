package com.moundou.bank.web;

import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.TransactionKind;
import com.moundou.bank.ledger.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite. repayments-new.html renders both steps with the model its comment
 * describes (MB-13). A stand-in controller feeds it, so this does not depend on the real
 * repayment controller, whose own tests check it builds the same model.
 *
 * Ama lent Ben $20.00 and Cleo lent him 50,000 CFA francs; Ben is signed in.
 */
@WebMvcTest(RepaymentTemplatesTests.Harness.class)
@Import({SecurityConfig.class, Formats.class, RepaymentTemplatesTests.Harness.class})
class RepaymentTemplatesTests {

    static final SignedInMember BEN = RolesAndProfileWebTests.MEMBER;
    static final UUID AMA = UUID.randomUUID();
    static final UUID CLEO = UUID.randomUUID();
    static final Map<UUID, String> NAMES = Map.of(AMA, "Ama", CLEO, "Cleo");

    static LedgerTransaction loan(UUID lender, long amountMinor, String currency, String note) {
        return new LedgerTransaction(UUID.randomUUID(), TransactionKind.LOAN, lender, BEN.memberId(), amountMinor,
                Currency.getInstance(currency), LocalDate.of(2026, 9, 28), note, TransactionStatus.APPROVED, null,
                lender, Instant.now(), Instant.now(), null, null, false, UUID.randomUUID());
    }

    static final LedgerTransaction FROM_AMA = loan(AMA, 2000, "USD", "Groceries");
    static final LedgerTransaction FROM_CLEO = loan(CLEO, 50_000, "XAF", null);

    /** The same component names the real request record will have. */
    record AsTyped(UUID loanId, String amountText, String currencyCode, UUID submissionKey) { }

    @Controller
    static class Harness {
        @GetMapping("/test/choose")
        String choose(Model model) {
            model.addAttribute("loans", List.of(FROM_AMA, FROM_CLEO));
            model.addAttribute("names", NAMES);
            model.addAttribute("messageKey", "repayment.sent");
            model.addAttribute("messageArgs", List.of("Ama", "$20.00"));
            return "repayments-new";
        }

        @GetMapping("/test/none")
        String none(Model model) {
            model.addAttribute("loans", List.of());
            model.addAttribute("names", Map.of());
            return "repayments-new";
        }

        @GetMapping("/test/form")
        String form(Model model) {
            model.addAttribute("loan", FROM_AMA);
            model.addAttribute("names", NAMES);
            model.addAttribute("submissionKey", UUID.randomUUID());
            model.addAttribute("currencies", List.of("EUR", "USD", "XAF"));
            return "repayments-new";
        }

        @GetMapping("/test/rejected")
        String rejected(Model model) {
            form(model);
            model.addAttribute("repayment", new AsTyped(FROM_AMA.id(), "15.00", "EUR", UUID.randomUUID()));
            model.addAttribute("errors", Map.of(
                    "loan", "ledger.repayment.loan.notOutstanding",
                    "amount", "ledger.repayment.amount.mismatch",
                    "currency", "ledger.repayment.currency.mismatch"));
            return "repayments-new";
        }
    }

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;

    @Test
    void stepOneListsEachLoanWithALinkToRepayIt() throws Exception {        // T-UI-6a, T-Repay-6a
        mvc.perform(get("/test/choose").with(oidcLogin().oidcUser(BEN)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Sent. Ama will be asked to confirm you repaid $20.00."),
                        containsString("You owe Ama $20.00"),
                        containsString("You owe Cleo "),
                        containsString("Borrowed on Sep 28, 2026"),
                        containsString("Groceries"),
                        containsString("/repayments/new?loan=" + FROM_AMA.id()),
                        not(containsString("type=\"checkbox\"")),
                        not(containsString("action=\"/repayments\"")))));
    }

    @Test
    void stepOneSaysSoWhenNothingIsOwed() throws Exception {
        mvc.perform(get("/test/none").with(oidcLogin().oidcUser(BEN)))
                .andExpect(content().string(containsString("You have no outstanding loans to repay.")));
    }

    @Test
    void stepTwoPrefillsTheLoansAmountAndCurrency() throws Exception {        // UI-6
        mvc.perform(get("/test/form").with(oidcLogin().oidcUser(BEN)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("You are repaying the $20.00 you borrowed from Ama on Sep 28, 2026"),
                        containsString("name=\"loanId\" value=\"" + FROM_AMA.id() + "\""),
                        containsString("value=\"20.00\""),
                        containsString("<option value=\"USD\" selected=\"selected\">"),
                        containsString("Ama confirms it before your balances change."),
                        containsString("action=\"/repayments\""),
                        containsString("name=\"_csrf\""))));
    }

    @Test
    void aRejectedRepaymentKeepsWhatWasTypedAndSaysWhy() throws Exception {  // UI-4
        mvc.perform(get("/test/rejected").with(oidcLogin().oidcUser(BEN)))
                .andExpect(content().string(allOf(
                        containsString("This loan is no longer outstanding"),
                        containsString("Repayment must match the outstanding loan amount exactly"),
                        containsString("Repayment must be in the loan&#39;s currency"),
                        containsString("value=\"15.00\""),
                        containsString("<option value=\"EUR\" selected=\"selected\">"))));
    }
}
