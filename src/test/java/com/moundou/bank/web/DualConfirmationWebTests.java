package com.moundou.bank.web;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.DualConfirmationService;
import com.moundou.bank.ledger.DualConfirmationService.Outcome;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.TransactionKind;
import com.moundou.bank.ledger.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite, no database. The MB-12 routes of DualConfirmationController: each acts as
 * the signed-in member, turns the service's outcome into the right redirect and flash
 * message, and lets a refusal through as 403. The rules themselves are tested in
 * DualConfirmationServiceIT; here the service is a stand-in.
 *
 * Ama is signed in. On the item below Ben recorded lending her $20.00, so she decides;
 * on the "sent" item she recorded lending Ben $20.00, so she may cancel.
 */
@WebMvcTest(DualConfirmationController.class)
@Import({SecurityConfig.class, Formats.class})
class DualConfirmationWebTests {

    static final SignedInMember AMA = RolesAndProfileWebTests.MEMBER;
    static final UUID BEN = UUID.randomUUID();
    static final Currency USD = Currency.getInstance("USD");

    static LedgerTransaction loan(UUID creditor, UUID debtor, UUID initiator) {
        return new LedgerTransaction(UUID.randomUUID(), TransactionKind.LOAN, creditor, debtor, 2000, USD,
                LocalDate.of(2026, 10, 1), null, TransactionStatus.PENDING, null, initiator,
                Instant.now(), null, null, null, false, UUID.randomUUID());
    }

    static final LedgerTransaction TO_DECIDE = loan(BEN, AMA.memberId(), BEN);
    static final LedgerTransaction SENT = loan(AMA.memberId(), BEN, AMA.memberId());
    static final Map<UUID, String> NAMES = Map.of(AMA.memberId(), "Ama", BEN, "Ben");

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;
    @MockBean DualConfirmationService approvals;
    @MockBean MemberDirectory members;

    @BeforeEach
    void family() {
        when(members.findById(AMA.memberId())).thenReturn(Optional.of(new Member(AMA.memberId(), "Ama", ZoneId.of("Africa/Douala"), true)));
        when(members.findById(BEN)).thenReturn(Optional.of(new Member(BEN, "Ben", ZoneId.of("Africa/Douala"), true)));
    }

    // ---- GET /pending/{id} --------------------------------------------------------

    @Test
    void anItemOpensForTheSignedInMember() throws Exception {                  // T-NPend-2a
        when(approvals.view(AMA.memberId(), TO_DECIDE.id())).thenReturn(new DualConfirmationService.View(TO_DECIDE, NAMES));

        mvc.perform(get("/pending/" + TO_DECIDE.id()).with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Ben says they lent you $20.00"),
                        containsString("/pending/" + TO_DECIDE.id() + "/decline"),
                        not(containsString("/approve")))));   // hidden until approving is built (PR 2)
    }

    @Test
    void anItemTheMemberMayNotSeeIsForbidden() throws Exception {             // Ledger.History-7
        when(approvals.view(any(), any())).thenThrow(new NotPermittedException(AMA.memberId(), "view"));

        mvc.perform(get("/pending/" + UUID.randomUUID()).with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isForbidden());
    }

    // ---- POST /pending/{id}/decline -----------------------------------------------

    @Test
    void aDeclineGoesHomeAndSaysTheInitiatorWillBeTold() throws Exception {   // Ledger.Approval-7, -10
        when(approvals.decline(AMA.memberId(), TO_DECIDE.id(), "not mine")).thenReturn(new Outcome.Recorded(TO_DECIDE));

        mvc.perform(post("/pending/" + TO_DECIDE.id() + "/decline").param("reason", "not mine")
                        .with(oidcLogin().oidcUser(AMA)).with(csrf()))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("messageKey", "decision.declined"))
                .andExpect(flash().attribute("messageArgs", List.of("Ben")));
    }

    @Test
    void aDeclineThatCameTooLateReturnsToTheItemWithTheReason() throws Exception {   // T-Approval-9a
        when(approvals.decline(any(), eq(TO_DECIDE.id()), any())).thenReturn(new Outcome.Rejected("ledger.approval.notPending"));

        mvc.perform(post("/pending/" + TO_DECIDE.id() + "/decline").with(oidcLogin().oidcUser(AMA)).with(csrf()))
                .andExpect(redirectedUrl("/pending/" + TO_DECIDE.id()))
                .andExpect(flash().attribute("errorKey", "ledger.approval.notPending"))
                .andExpect(flash().attributeCount(1));
    }

    // ---- POST /transactions/{id}/cancel -------------------------------------------

    @Test
    void aCancelGoesHomeAndNamesTheOtherParty() throws Exception {            // Ledger.Approval-11
        when(approvals.cancel(AMA.memberId(), SENT.id())).thenReturn(new Outcome.Recorded(SENT));

        mvc.perform(post("/transactions/" + SENT.id() + "/cancel").with(oidcLogin().oidcUser(AMA)).with(csrf()))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("messageKey", "decision.cancelled"))
                .andExpect(flash().attribute("messageArgs", List.of("Ben")));
    }

    @Test
    void theOtherPartyIsFoundWhicheverSideTheInitiatorIsOn() throws Exception {
        // Ama recorded this one but is its debtor (she says Ben lent her $20.00), so the
        // controller has to take the creditor as the other party.
        LedgerTransaction sentAsDebtor = loan(BEN, AMA.memberId(), AMA.memberId());
        when(approvals.cancel(AMA.memberId(), sentAsDebtor.id())).thenReturn(new Outcome.Recorded(sentAsDebtor));

        mvc.perform(post("/transactions/" + sentAsDebtor.id() + "/cancel").with(oidcLogin().oidcUser(AMA)).with(csrf()))
                .andExpect(flash().attribute("messageArgs", List.of("Ben")));
    }

    @Test
    void aCancelThatCameTooLateReturnsToTheItemWithOnlyTheError() throws Exception {   // T-Approval-12a
        when(approvals.cancel(any(), eq(SENT.id()))).thenReturn(new Outcome.Rejected("ledger.approval.notPending"));

        mvc.perform(post("/transactions/" + SENT.id() + "/cancel").with(oidcLogin().oidcUser(AMA)).with(csrf()))
                .andExpect(redirectedUrl("/pending/" + SENT.id()))
                .andExpect(flash().attribute("errorKey", "ledger.approval.notPending"))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    void aCancelByTheWrongMemberIsForbidden() throws Exception {              // T-Approval-12a
        when(approvals.cancel(any(), any())).thenThrow(new NotPermittedException(AMA.memberId(), "cancel"));

        mvc.perform(post("/transactions/" + TO_DECIDE.id() + "/cancel").with(oidcLogin().oidcUser(AMA)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- every change acts as the signed-in member, with a CSRF token -------------

    @Test
    void everyDecisionNeedsACsrfToken() throws Exception {
        mvc.perform(post("/pending/" + TO_DECIDE.id() + "/decline").with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/transactions/" + SENT.id() + "/cancel").with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isForbidden());
        verify(approvals, never()).decline(any(), any(), any());
        verify(approvals, never()).cancel(any(), any());
    }
}
