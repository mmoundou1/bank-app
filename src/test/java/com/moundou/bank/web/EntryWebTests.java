package com.moundou.bank.web;

import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.Member;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.EntryRequest;
import com.moundou.bank.ledger.EntryService;
import com.moundou.bank.ledger.EntryValidator;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.TransactionKind;
import com.moundou.bank.ledger.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite, no database. The entry screen of MB-11: who reaches it, that it always acts
 * as the signed-in member, what it shows after each outcome, and that the browser's form
 * turns into the right EntryRequest. The rules themselves are tested in
 * EntryValidatorTests and EntryServiceIT; here the service is a stand-in.
 */
@WebMvcTest(EntryController.class)
@Import({SecurityConfig.class, Formats.class})
class EntryWebTests {

    static final SignedInMember AMA = RolesAndProfileWebTests.MEMBER;   // signed in, an ordinary member
    static final UUID BEN = UUID.randomUUID();
    static final UUID KEY = UUID.randomUUID();
    static final Currency USD = Currency.getInstance("USD");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;
    @MockBean EntryService entries;
    @MockBean EntryValidator validator;
    @MockBean MemberDirectory members;

    @BeforeEach
    void family() {
        when(members.findById(AMA.memberId())).thenReturn(Optional.of(member(AMA.memberId(), "Ama", true)));
        when(members.findById(BEN)).thenReturn(Optional.of(member(BEN, "Ben", true)));
        when(members.counterpartiesFor(AMA.memberId())).thenReturn(List.of(member(BEN, "Ben", true)));
        when(validator.today(any())).thenReturn(TODAY);
        when(entries.submissionKey()).thenReturn(KEY);
    }

    static Member member(UUID id, String name, boolean active) {
        return new Member(id, name, ZoneId.of("Africa/Douala"), active);
    }

    /** A saved $20.00 loan from {@code creditor} to {@code debtor} on Sep 30. */
    static LedgerTransaction loan(UUID creditor, UUID debtor) {
        return new LedgerTransaction(UUID.randomUUID(), TransactionKind.LOAN, creditor, debtor, 2000, USD,
                LocalDate.of(2026, 9, 30), null, TransactionStatus.PENDING, null, AMA.memberId(),
                Instant.now(), Instant.now(), null, null, false, KEY);
    }

    /** The form as a browser sends it: Ama lent Ben $20.00, nothing ticked. */
    static MockHttpServletRequestBuilder submit() {
        return post("/transactions")
                .param("submissionKey", KEY.toString())
                .param("openingBalance", "false")
                .param("counterpartyId", BEN.toString())
                .param("role", "LENDER")
                .param("_split", "on")              // the box is unticked, so only its marker arrives
                .param("amountText", "20.00")
                .param("currencyCode", "USD")
                .param("date", "2026-09-30")
                .param("note", "taxi")
                .with(oidcLogin().oidcUser(AMA)).with(csrf());
    }

    // ---- reaching the form ------------------------------------------------------------

    @Test
    void nobodySignedInIsSentToSignIn() throws Exception {   // Auth.Login-2
        mvc.perform(get("/transactions/new")).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void theFormOffersTheFamilyTheCurrenciesAndTodayWhereTheMemberLives() throws Exception {
        mvc.perform(get("/transactions/new").with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("name=\"submissionKey\" value=\"" + KEY + "\""),   // Ledger.Entry-14
                        containsString("<option value=\"" + BEN + "\">Ben</option>"),
                        containsString("<option value=\"EUR\">Euros (EUR)</option>"),
                        containsString("value=\"2026-10-04\""),                         // Ledger.Entry-8, -12
                        containsString("max=\"2026-10-04\""),
                        // The tests below send the form by hand. These two lines tie them to the real page:
                        containsString("name=\"_split\""),                              // an unticked box still arrives as false
                        containsString("name=\"openingBalance\" value=\"false\""))));
    }

    @Test
    void aDeactivatedMemberWithAnOldSessionIsRefusedTheForm() throws Exception {   // Auth.Login-5
        when(members.findById(AMA.memberId())).thenReturn(Optional.of(member(AMA.memberId(), "Ama", false)));
        mvc.perform(get("/transactions/new").with(oidcLogin().oidcUser(AMA))).andExpect(status().isForbidden());
    }

    @Test
    void theBrowserIsToldNotToKeepTheForm() throws Exception {   // MB-11 decision B: Back fetches a fresh key
        mvc.perform(get("/transactions/new").with(oidcLogin().oidcUser(AMA)))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    // ---- submitting -------------------------------------------------------------------

    @Test
    void aSubmissionAlwaysActsAsTheSignedInMember() throws Exception {   // T-Sec-2a, web half
        when(entries.submit(any(), any(), any())).thenReturn(new EntryService.Outcome.Recorded(loan(AMA.memberId(), BEN), false));

        mvc.perform(submit().param("initiatorId", BEN.toString()));   // an attempt to act as Ben is ignored

        verify(entries).submit(eq(AMA.memberId()), any(), any());
        verify(entries, never()).submit(eq(BEN), any(), any());
    }

    @Test
    void submittingWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/transactions").param("amountText", "20").with(oidcLogin().oidcUser(AMA)))
                .andExpect(status().isForbidden());
        verify(entries, never()).submit(any(), any(), any());
    }

    @Test
    void theFormArrivesAsTheEntryRequestTheMemberFilledIn() throws Exception {
        when(entries.submit(any(), any(), any())).thenReturn(new EntryService.Outcome.Recorded(loan(AMA.memberId(), BEN), false));
        ArgumentCaptor<EntryRequest> sent = ArgumentCaptor.forClass(EntryRequest.class);

        mvc.perform(submit());

        verify(entries).submit(eq(AMA.memberId()), sent.capture(), any());
        assertThat(sent.getValue()).isEqualTo(new EntryRequest(BEN, EntryRequest.Role.LENDER, "20.00", "USD",
                LocalDate.of(2026, 9, 30), "taxi", false, false, KEY));   // an unticked split arrives as false
    }

    @Test
    void aTickedSplitArrivesAsASplit() throws Exception {   // Ledger.Entry-7
        when(entries.submit(any(), any(), any())).thenReturn(new EntryService.Outcome.Recorded(loan(AMA.memberId(), BEN), false));
        ArgumentCaptor<EntryRequest> sent = ArgumentCaptor.forClass(EntryRequest.class);

        mvc.perform(submit().param("split", "true"));

        verify(entries).submit(any(), sent.capture(), any());
        assertThat(sent.getValue().split()).isTrue();
    }

    // ---- after a save: back to a fresh form, saying what was recorded -----------------

    @Test
    void aNewLoanSaysItWasSentForApproval() throws Exception {
        when(entries.submit(any(), any(), any())).thenReturn(new EntryService.Outcome.Recorded(loan(AMA.memberId(), BEN), false));
        mvc.perform(submit())
                .andExpect(redirectedUrl("/transactions/new"))
                .andExpect(flash().attribute("messageKey", "entry.sent.lent"))
                .andExpect(flash().attribute("messageArgs", List.of("Ben", "$20.00", "Sep 30, 2026")));
    }

    @Test
    void theDirectionComesFromTheSavedTransactionNotTheForm() throws Exception {
        // The form says LENDER, but the record has Ben as creditor: the message must follow the record.
        when(entries.submit(any(), any(), any())).thenReturn(new EntryService.Outcome.Recorded(loan(BEN, AMA.memberId()), false));
        mvc.perform(submit()).andExpect(flash().attribute("messageKey", "entry.sent.borrowed"));
    }

    @Test
    void aRetryNamesTheEntryThatWasAlreadyRecorded() throws Exception {   // Ledger.Entry-14, MB-11 decision A
        when(entries.submit(any(), any(), any())).thenReturn(new EntryService.Outcome.Recorded(loan(AMA.memberId(), BEN), true));
        mvc.perform(submit())
                .andExpect(redirectedUrl("/transactions/new"))
                .andExpect(flash().attribute("messageKey", "entry.already.lent"));
    }

    @Test
    void theMessageIsShownAboveAFreshFormAfterTheRedirect() throws Exception {
        when(entries.submit(any(), any(), any())).thenReturn(new EntryService.Outcome.Recorded(loan(AMA.memberId(), BEN), true));
        MvcResult saved = mvc.perform(submit()).andReturn();

        mvc.perform(get("/transactions/new").flashAttrs(saved.getFlashMap()).with(oidcLogin().oidcUser(AMA)))
                .andExpect(content().string(allOf(
                        containsString("This was already sent: you lent Ben $20.00 on Sep 30, 2026."),
                        containsString("name=\"submissionKey\" value=\"" + KEY + "\""))));   // the stubbed fresh key
    }

    // ---- after a refusal: the same form, with every error beside its field (UI-4) -----

    @Test
    void aRejectedEntryKeepsWhatWasTypedAndShowsEachError() throws Exception {
        when(entries.submit(any(), any(), any())).thenReturn(new EntryService.Outcome.Rejected(Map.of(
                EntryValidator.AMOUNT, "ledger.entry.amount.notPositive",
                EntryValidator.DATE, "ledger.entry.date.future")));

        mvc.perform(submit())
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Amount must be greater than zero"),         // Ledger.Entry-2, SRS wording
                        containsString("The date cannot be in the future"),
                        containsString("<option value=\"" + BEN + "\" selected=\"selected\">Ben</option>"),
                        containsString("value=\"taxi\""),
                        containsString("name=\"submissionKey\" value=\"" + KEY + "\""))));   // the same key: nothing was saved
    }
}
