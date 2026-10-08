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
import org.springframework.web.bind.annotation.RequestParam;

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
 * Fast suite, no database. The MB-12 templates render with the model their comments
 * describe, and word each item from the viewer's side (USE-3). A stand-in controller
 * feeds them, so these tests do not depend on the real approval controller; that
 * controller's own tests check it puts the same model together.
 */
@WebMvcTest(ApprovalTemplatesTests.Harness.class)
@Import({SecurityConfig.class, Formats.class, ApprovalTemplatesTests.Harness.class})
class ApprovalTemplatesTests {

    static final SignedInMember AMA = RolesAndProfileWebTests.MEMBER;
    static final UUID BEN = UUID.randomUUID();
    static final Currency USD = Currency.getInstance("USD");
    static final Map<UUID, String> NAMES = Map.of(AMA.memberId(), "Ama", BEN, "Ben");

    static LedgerTransaction pending(TransactionKind kind, UUID creditor, UUID debtor, UUID initiator) {
        return new LedgerTransaction(UUID.randomUUID(), kind, creditor, debtor, 2000, USD,
                LocalDate.of(2026, 10, 1), "Groceries", TransactionStatus.PENDING, null, initiator,
                Instant.now(), null, null, null, false, UUID.randomUUID());
    }

    /** Ben lent Ama $20.00 and is waiting for her. */
    static final LedgerTransaction BEN_LENT_AMA = pending(TransactionKind.LOAN, BEN, AMA.memberId(), BEN);
    /** Ama recorded repaying Ben $20.00: on a repayment the payer is the creditor (SRS 4.2). */
    static final LedgerTransaction AMA_REPAID_BEN = pending(TransactionKind.REPAYMENT, AMA.memberId(), BEN, AMA.memberId());

    @Controller
    static class Harness {
        @GetMapping("/test/home")
        String home(Model model) {
            model.addAttribute("displayName", "Ama");
            model.addAttribute("toDecide", List.of(BEN_LENT_AMA));
            model.addAttribute("waiting", List.of(AMA_REPAID_BEN));
            model.addAttribute("names", NAMES);
            model.addAttribute("messageKey", "decision.declined");
            model.addAttribute("messageArgs", List.of("Ben"));
            return "home";
        }

        @GetMapping("/test/pending")
        String pending(@RequestParam boolean mine, Model model) {
            model.addAttribute("item", mine ? AMA_REPAID_BEN : BEN_LENT_AMA);
            model.addAttribute("names", NAMES);
            model.addAttribute("errorKey", "ledger.approval.notPending");
            model.addAttribute("approveReady", true);   // hidden in production until MB-12's PR 2
            return "pending";
        }
    }

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;

    @Test
    void homeListsBothQueuesFromTheViewersSide() throws Exception {
        mvc.perform(get("/test/home").with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Declined. Ben will be told."),
                        containsString("Ben says they lent you $20.00"),
                        containsString("You repaid Ben $20.00"),
                        containsString("Waiting for Ben"),
                        containsString("/pending/" + BEN_LENT_AMA.id()))));
    }

    @Test
    void theCounterpartyIsOfferedApproveAndDecline() throws Exception {
        mvc.perform(get("/test/pending").param("mine", "false").with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Transaction no longer pending"),
                        containsString("Ben says they lent you $20.00"),
                        containsString("/pending/" + BEN_LENT_AMA.id() + "/approve"),
                        containsString("/pending/" + BEN_LENT_AMA.id() + "/decline"),
                        containsString("name=\"reason\""),
                        not(containsString("/cancel")))));
    }

    @Test
    void theInitiatorIsOfferedCancelOnly() throws Exception {
        mvc.perform(get("/test/pending").param("mine", "true").with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("You repaid Ben $20.00"),
                        containsString("Waiting for Ben to decide."),
                        containsString("Ben&#39;s list"),
                        containsString("/transactions/" + AMA_REPAID_BEN.id() + "/cancel"),
                        not(containsString("/approve")),
                        not(containsString("/decline")))));
    }
}
