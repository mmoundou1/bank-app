package com.moundou.bank.web;

import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.HistoryDirection;
import com.moundou.bank.ledger.HistoryEntry;
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
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;

import static com.moundou.bank.ledger.HistoryDirection.CORRECTION;
import static com.moundou.bank.ledger.HistoryDirection.YOU_BORROWED;
import static com.moundou.bank.ledger.HistoryDirection.YOU_LENT;
import static com.moundou.bank.ledger.HistoryDirection.YOU_REPAID;
import static com.moundou.bank.ledger.HistoryDirection.YOU_WERE_REPAID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite. history.html renders each card from the model its comment describes
 * (MB-14). A stand-in controller feeds it, so this does not depend on HistoryController,
 * whose own tests check it builds the same model. Ben is signed in, in New York.
 */
@WebMvcTest(HistoryTemplatesTests.Harness.class)
@Import({SecurityConfig.class, Formats.class, HistoryTemplatesTests.Harness.class})
class HistoryTemplatesTests {

    static final SignedInMember BEN = RolesAndProfileWebTests.MEMBER;
    static final Currency USD = Currency.getInstance("USD");
    static final Currency XAF = Currency.getInstance("XAF");
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** 2026-10-04 18:02 UTC: 2:02 PM in New York. */
    static final Instant DECIDED = Instant.parse("2026-10-04T18:02:00Z");

    static HistoryEntry entry(HistoryDirection direction, boolean recordedByYou, String note,
                              TransactionStatus status, String declineReason, Instant decidedAt) {
        return new HistoryEntry(direction, "Ama", recordedByYou, 2000, USD, LocalDate.of(2026, 10, 3),
                note, status, declineReason, decidedAt, false, null, null);
    }

    @Controller
    static class Harness {
        static List<HistoryEntry> entries = List.of();

        @GetMapping("/test/history")
        String history(Model model) {
            model.addAttribute("entries", entries);
            model.addAttribute("timeZone", NEW_YORK);
            return "history";
        }
    }

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;

    private String render(HistoryEntry... entries) throws Exception {
        Harness.entries = List.of(entries);
        return mvc.perform(get("/test/history").with(oidcLogin().oidcUser(BEN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void eachDirectionReadsAsASentenceFromTheViewersSide() throws Exception {   // USE-3, T-Hist-6a
        String page = render(
                entry(YOU_LENT, true, null, TransactionStatus.APPROVED, null, DECIDED),
                entry(YOU_BORROWED, false, null, TransactionStatus.APPROVED, null, DECIDED),
                entry(YOU_REPAID, true, null, TransactionStatus.APPROVED, null, DECIDED),
                entry(YOU_WERE_REPAID, false, null, TransactionStatus.APPROVED, null, DECIDED));

        assertThat(page).contains(
                "You lent Ama $20.00",
                "You borrowed $20.00 from Ama",
                "You paid Ama back $20.00",
                "Ama paid you back $20.00",
                "Loan", "Repayment",
                "Oct 3, 2026");
    }

    @Test
    void aCorrectionQuotesTheSentenceOfWhatItUndoes() throws Exception {
        HistoryEntry correction = new HistoryEntry(CORRECTION, "Ama", false, 2000, USD, LocalDate.of(2026, 10, 10),
                null, TransactionStatus.PENDING, null, null, false, YOU_BORROWED, LocalDate.of(2026, 9, 3));

        String page = render(correction);
        assertThat(page)
                .contains("Correction: undoes “You borrowed $20.00 from Ama” from Sep 3, 2026")
                .contains("Correction")
                .contains("Oct 10, 2026");
    }

    @Test
    void everyStatusIsLabelledAndADecisionShowsItsTimeInTheViewersZone() throws Exception {   // T-Hist-2a
        String page = render(
                entry(YOU_LENT, true, null, TransactionStatus.PENDING, null, null),
                entry(YOU_LENT, true, null, TransactionStatus.APPROVED, null, DECIDED),
                entry(YOU_LENT, true, null, TransactionStatus.DECLINED, null, DECIDED),
                entry(YOU_LENT, true, null, TransactionStatus.CANCELLED, null, DECIDED));

        assertThat(page.replace(' ', ' ')).contains(
                "Pending approval · not decided yet",
                "Approved on Oct 4, 2026, 2:02 PM",           // 18:02 UTC, shown in New York time
                "Declined on Oct 4, 2026, 2:02 PM",
                "Cancelled on Oct 4, 2026, 2:02 PM");
    }

    @Test
    void aDeclinedRecordShowsItsReason() throws Exception {                    // T-Hist-3a
        String page = render(entry(YOU_BORROWED, false, null, TransactionStatus.DECLINED, "Wrong amount", DECIDED));
        assertThat(page).contains("Reason: Wrong amount");
    }

    @Test
    void whoRecordedItTheNoteAndAnOpeningBalanceAreShown() throws Exception {  // T-Hist-6a
        HistoryEntry opening = new HistoryEntry(YOU_BORROWED, "Ama", false, 150_000, XAF, LocalDate.of(2019, 6, 1),
                "School fees", TransactionStatus.APPROVED, null, DECIDED, true, null, null);

        String page = render(opening, entry(YOU_LENT, true, null, TransactionStatus.PENDING, null, null));
        assertThat(page)
                .contains("Recorded by Ama", "Recorded by you", "School fees", "Opening balance", "Jun 1, 2019");
    }

    @Test
    void emptyThingsAreLeftOut() throws Exception {
        String page = render(entry(YOU_LENT, true, null, TransactionStatus.PENDING, null, null));
        assertThat(page)
                .doesNotContain("Reason:", "Opening balance", "Approved on", "No transactions yet.");
    }

    @Test
    void noRowHasAnyControl() throws Exception {                               // T-Hist-4a
        String page = render(
                entry(YOU_LENT, true, null, TransactionStatus.PENDING, null, null),
                entry(YOU_BORROWED, false, null, TransactionStatus.APPROVED, null, DECIDED),
                entry(YOU_LENT, true, null, TransactionStatus.DECLINED, "No", DECIDED),
                entry(YOU_LENT, true, null, TransactionStatus.CANCELLED, null, DECIDED));
        // Only the list: the navigation above it has its own sign-out form.
        String list = page.substring(page.indexOf("<main>"), page.indexOf("</main>"));

        assertThat(list)
                .contains("You lent Ama $20.00")
                .doesNotContain("<form", "<button", "<input", "<a ");
    }

    @Test
    void withNoTransactionsThePageSaysSo() throws Exception {
        assertThat(render()).contains("No transactions yet.");
    }
}
