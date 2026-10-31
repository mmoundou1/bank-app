package com.moundou.bank.web;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.CompensationService;
import com.moundou.bank.ledger.CompensationService.Outcome;
import com.moundou.bank.ledger.CompensationService.UndoTarget;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.TransactionKind;
import com.moundou.bank.ledger.TransactionStatus;
import com.moundou.bank.ledger.UndoRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite, no database. The undo screen and its POST (MB-14 journey 2): who reaches
 * them, that they act as the signed-in member, and what each service answer turns into.
 * The rules are CompensationService's and UndoRules's, tested in CompensationServiceIT
 * and UndoRulesTests. Ama lent Ben $20.00; Ben is signed in.
 */
@WebMvcTest(CompensationController.class)
@Import({SecurityConfig.class, Formats.class})
class CompensationWebTests {

    static final SignedInMember BEN = RolesAndProfileWebTests.MEMBER;
    static final UUID AMA = UUID.randomUUID();
    static final Currency USD = Currency.getInstance("USD");

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;
    @MockBean CompensationService compensations;
    @MockBean MemberDirectory members;

    final LedgerTransaction loan = row(TransactionKind.LOAN, AMA, BEN.memberId(), null);
    final String undoUrl = "/history/" + loan.id() + "/undo";

    @BeforeEach
    void family() {
        when(members.findById(BEN.memberId())).thenReturn(Optional.of(EntryWebTests.member(BEN.memberId(), "Ben", true)));
        when(members.findById(AMA)).thenReturn(Optional.of(EntryWebTests.member(AMA, "Ama", true)));
        when(compensations.targetDetails(BEN.memberId(), loan.id()))
                .thenReturn(new UndoTarget(loan, new UndoRules.Check.Undoable()));
    }

    @Test
    void nobodySignedInIsSentToSignIn() throws Exception {                     // Auth.Login-2
        mvc.perform(get(undoUrl)).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void theScreenShowsWhatIsUndoneWithTheAmountFixed() throws Exception {      // T-Hist-10a
        mvc.perform(get(undoUrl).with(oidcLogin().oidcUser(BEN)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("You borrowed $20.00 from Ama"),
                        containsString("Sep 3, 2026"),
                        containsString("Amount: $20.00 (can&#39;t be changed)"),
                        containsString("Ama confirms it before your balances change."),
                        containsString("action=\"" + undoUrl + "\""),
                        containsString("name=\"submissionKey\""),
                        containsString("name=\"note\""),
                        not(containsString("name=\"amount\"")),
                        containsString("name=\"_csrf\""),
                        containsString("Send to Ama"))));
    }

    @Test
    void aRecordThatCannotBeUndoneLeadsBackToTheHistorySayingWhy() throws Exception {
        when(compensations.targetDetails(BEN.memberId(), loan.id())).thenReturn(new UndoTarget(loan,
                new UndoRules.Check.Refused("ledger.history.correction.conflictsWithRepayment")));

        mvc.perform(get(undoUrl).with(oidcLogin().oidcUser(BEN)))
                .andExpect(redirectedUrl("/history"))
                .andExpect(flash().attribute("errorKey", "ledger.history.correction.conflictsWithRepayment"));
    }

    @Test
    void someoneElsesRecordIsForbidden() throws Exception {                     // T-Hist-9b
        when(compensations.targetDetails(any(), any())).thenThrow(new NotPermittedException(BEN.memberId(), "view"));
        mvc.perform(get(undoUrl).with(oidcLogin().oidcUser(BEN))).andExpect(status().isForbidden());
    }

    @Test
    void aPostWithoutTheFormsTokenIsRefused() throws Exception {                // CSRF
        mvc.perform(post(undoUrl).with(oidcLogin().oidcUser(BEN)).param("submissionKey", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        verify(compensations, never()).compensation(any(), any(), any(), any());
    }

    @Test
    void aPostWithoutASubmissionKeyIsABadRequest() throws Exception {
        mvc.perform(post(undoUrl).with(oidcLogin().oidcUser(BEN)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSentUndoActsAsTheSignedInMemberAndSaysWhoWillBeAsked() throws Exception {
        UUID key = UUID.randomUUID();
        LedgerTransaction correction = row(TransactionKind.COMPENSATING, BEN.memberId(), AMA, loan.id());
        when(compensations.compensation(BEN.memberId(), loan.id(), "Never happened", key))
                .thenReturn(new Outcome.Recorded(correction, false));

        mvc.perform(post(undoUrl).with(oidcLogin().oidcUser(BEN)).with(csrf())
                        .param("note", "Never happened").param("submissionKey", key.toString()))
                .andExpect(redirectedUrl("/history"))
                .andExpect(flash().attribute("messageKey", "history.undo.sent"))
                .andExpect(flash().attribute("messageArgs", List.of("Ama")));
    }

    @Test
    void aNoteIsOptional() throws Exception {
        UUID key = UUID.randomUUID();
        when(compensations.compensation(eq(BEN.memberId()), eq(loan.id()), isNull(), eq(key)))
                .thenReturn(new Outcome.Recorded(row(TransactionKind.COMPENSATING, BEN.memberId(), AMA, loan.id()), false));

        mvc.perform(post(undoUrl).with(oidcLogin().oidcUser(BEN)).with(csrf()).param("submissionKey", key.toString()))
                .andExpect(redirectedUrl("/history"));
    }

    @Test
    void aRefusedUndoLeadsBackToTheHistorySayingWhy() throws Exception {        // T-Hist-11a
        when(compensations.compensation(any(), any(), any(), any()))
                .thenReturn(new Outcome.Rejected("ledger.history.correction.conflictsWithRepayment"));

        mvc.perform(post(undoUrl).with(oidcLogin().oidcUser(BEN)).with(csrf())
                        .param("submissionKey", UUID.randomUUID().toString()))
                .andExpect(redirectedUrl("/history"))
                .andExpect(flash().attribute("errorKey", "ledger.history.correction.conflictsWithRepayment"));
    }

    static LedgerTransaction row(TransactionKind kind, UUID creditor, UUID debtor, UUID corrects) {
        return new LedgerTransaction(UUID.randomUUID(), kind, creditor, debtor, 2000, USD, LocalDate.of(2026, 9, 3),
                null, corrects == null ? TransactionStatus.APPROVED : TransactionStatus.PENDING, null, creditor,
                Instant.now(), Instant.now(), null, corrects, false, UUID.randomUUID());
    }
}
