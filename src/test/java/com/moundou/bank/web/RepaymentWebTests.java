package com.moundou.bank.web;

import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.MemberDirectory;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.RepaymentService;
import com.moundou.bank.ledger.RepaymentService.Outcome;
import com.moundou.bank.ledger.RepaymentService.RepaymentRequest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite, no database. The repayment screens of MB-13: who reaches them, that they
 * act as the signed-in member, and what each service answer turns into. The rules are
 * RepaymentService's and are tested against Postgres in RepaymentServiceIT; here the
 * service is a stand-in.
 */
@WebMvcTest(RepaymentController.class)
@Import({SecurityConfig.class, Formats.class})
class RepaymentWebTests {

    static final SignedInMember BEN = RolesAndProfileWebTests.MEMBER;   // signed in, the borrower
    static final UUID AMA = UUID.randomUUID();                          // the lender
    static final UUID KEY = UUID.randomUUID();
    static final Currency USD = Currency.getInstance("USD");

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;
    @MockBean RepaymentService repayments;
    @MockBean MemberDirectory members;

    final LedgerTransaction loan = loan();

    @BeforeEach
    void family() {
        when(members.findById(BEN.memberId())).thenReturn(Optional.of(EntryWebTests.member(BEN.memberId(), "Ben", true)));
        when(members.findById(AMA)).thenReturn(Optional.of(EntryWebTests.member(AMA, "Ama", true)));
        when(repayments.outstandingLoansOwedBy(BEN.memberId())).thenReturn(List.of(loan));
        when(repayments.viewTransaction(BEN.memberId(), loan.id())).thenReturn(Optional.of(loan));
    }

    /** Ama lent Ben $20.00 on Sep 28, approved. */
    static LedgerTransaction loan() {
        return new LedgerTransaction(UUID.randomUUID(), TransactionKind.LOAN, AMA, BEN.memberId(), 2000, USD,
                LocalDate.of(2026, 9, 28), null, TransactionStatus.APPROVED, null, AMA,
                Instant.now(), Instant.now(), null, null, false, UUID.randomUUID());
    }

    /** The repayment the service would save for that loan: Ben is the creditor now (D2). */
    LedgerTransaction repayment() {
        return new LedgerTransaction(UUID.randomUUID(), TransactionKind.REPAYMENT, BEN.memberId(), AMA, 2000, USD,
                LocalDate.of(2026, 10, 18), null, TransactionStatus.PENDING, null, BEN.memberId(),
                Instant.now(), null, loan.id(), null, false, KEY);
    }

    MockHttpServletRequestBuilder submit() {
        return submit("20.00");
    }

    MockHttpServletRequestBuilder submit(String amountText) {
        return post("/repayments")
                .param("loanId", loan.id().toString())
                .param("amountText", amountText)
                .param("currencyCode", "USD")
                .param("submissionKey", KEY.toString())
                .with(oidcLogin().oidcUser(BEN)).with(csrf());
    }

    // ---- reaching the screens ---------------------------------------------------------

    @Test
    void nobodySignedInIsSentToSignIn() throws Exception {   // Auth.Login-2
        mvc.perform(get("/repayments/new")).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void aPostWithoutTheFormsTokenIsRefused() throws Exception {   // SEC-3, CSRF
        mvc.perform(post("/repayments").with(oidcLogin().oidcUser(BEN))).andExpect(status().isForbidden());
        verify(repayments, never()).submit(any(), any(), any());
    }

    // ---- step 1: the list -------------------------------------------------------------

    @Test
    void theListShowsWhatIsOwedToWhomWithALinkToRepayIt() throws Exception {   // Ledger.Repayment-8
        mvc.perform(get("/repayments/new").with(oidcLogin().oidcUser(BEN)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("You owe Ama $20.00"),
                        containsString("href=\"/repayments/new?loan=" + loan.id() + "\""),
                        not(containsString("action=\"/repayments\"")))));
    }

    @Test
    void withNothingOwedTheListSaysSo() throws Exception {
        when(repayments.outstandingLoansOwedBy(BEN.memberId())).thenReturn(List.of());
        mvc.perform(get("/repayments/new").with(oidcLogin().oidcUser(BEN)))
                .andExpect(content().string(containsString("You have no outstanding loans to repay.")));
    }

    // ---- step 2: the form -------------------------------------------------------------

    @Test
    void theFormIsForThatLoanWithItsAmountFilledIn() throws Exception {   // UI-6
        mvc.perform(get("/repayments/new").param("loan", loan.id().toString()).with(oidcLogin().oidcUser(BEN)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("action=\"/repayments\""),
                        containsString("name=\"loanId\" value=\"" + loan.id() + "\""),
                        containsString("name=\"submissionKey\""),                   // Ledger.Entry-14
                        containsString("value=\"20.00\""),
                        containsString("<option value=\"USD\" selected=\"selected\">"))));
    }

    @Test
    void aLoanThatCannotBeRepaidLeadsBackToTheList() throws Exception {   // T-Repay-10
        UUID gone = UUID.randomUUID();
        when(repayments.viewTransaction(BEN.memberId(), gone)).thenReturn(Optional.empty());
        mvc.perform(get("/repayments/new").param("loan", gone.toString()).with(oidcLogin().oidcUser(BEN)))
                .andExpect(redirectedUrl("/repayments/new"))
                .andExpect(flash().attribute("errorKey", "ledger.repayment.loan.notOutstanding"));
    }

    // ---- POST /repayments -------------------------------------------------------------

    @Test
    void theFormBecomesARequestActingAsTheSignedInMember() throws Exception {
        when(repayments.submit(any(), any(), any())).thenReturn(new Outcome.Recorded(repayment(), false));
        mvc.perform(submit());

        ArgumentCaptor<RepaymentRequest> sent = ArgumentCaptor.forClass(RepaymentRequest.class);
        verify(repayments).submit(eq(BEN.memberId()), sent.capture(), any());
        assertThat(sent.getValue()).isEqualTo(new RepaymentRequest(loan.id(), "20.00", "USD", KEY));
    }

    @Test
    void aSavedRepaymentSaysWhoWillBeAskedAndGoesBackToTheList() throws Exception {   // Ledger.Repayment-3
        when(repayments.submit(any(), any(), any())).thenReturn(new Outcome.Recorded(repayment(), false));
        mvc.perform(submit())
                .andExpect(redirectedUrl("/repayments/new"))
                .andExpect(flash().attribute("messageKey", "repayment.sent"))
                .andExpect(flash().attribute("messageArgs", List.of("Ama", "$20.00")));
    }

    @Test
    void aRetrySaysItWasAlreadySent() throws Exception {   // Ledger.Entry-14
        when(repayments.submit(any(), any(), any())).thenReturn(new Outcome.Recorded(repayment(), true));
        mvc.perform(submit())
                .andExpect(flash().attribute("messageKey", "repayment.already"));
    }

    @Test
    void aRejectedRepaymentShowsTheFormAgainAsTypedWithTheSameKey() throws Exception {   // UI-4
        when(repayments.submit(any(), any(), any()))
                .thenReturn(new Outcome.Rejected(Map.of("amount", "ledger.repayment.amount.mismatch")));
        mvc.perform(submit("15.00"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Repayment must match the outstanding loan amount exactly"),
                        containsString("value=\"15.00\""),
                        containsString("name=\"submissionKey\" value=\"" + KEY + "\""))));
    }

    @Test
    void aRejectionForALoanNoLongerRepayableGoesBackToTheList() throws Exception {   // Ledger.Repayment-10
        when(repayments.submit(any(), any(), any()))
                .thenReturn(new Outcome.Rejected(Map.of("loan", "ledger.repayment.loan.notOutstanding")));
        when(repayments.viewTransaction(BEN.memberId(), loan.id())).thenReturn(Optional.empty());
        mvc.perform(submit())
                .andExpect(redirectedUrl("/repayments/new"))
                .andExpect(flash().attribute("errorKey", "ledger.repayment.loan.notOutstanding"));
    }
}
