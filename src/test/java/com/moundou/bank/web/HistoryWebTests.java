package com.moundou.bank.web;

import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.ProfileRules;
import com.moundou.bank.identity.ProfileService;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.HistoryDirection;
import com.moundou.bank.ledger.HistoryEntry;
import com.moundou.bank.ledger.HistoryService;
import com.moundou.bank.ledger.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Fast suite, no database. GET /history (MB-14): who reaches it, that it shows the
 * signed-in member's own history, and the model it hands history.html. The rules are
 * HistoryService's and are tested against Postgres in HistoryServiceIT.
 */
@WebMvcTest(HistoryController.class)
@Import({SecurityConfig.class, Formats.class})
class HistoryWebTests {

    static final SignedInMember BEN = RolesAndProfileWebTests.MEMBER;
    static final ZoneId DOUALA = ZoneId.of("Africa/Douala");

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;
    @MockBean HistoryService history;
    @MockBean ProfileService profiles;

    final List<HistoryEntry> entries = List.of(new HistoryEntry(HistoryDirection.YOU_BORROWED, "Ama", false,
            2000, Currency.getInstance("USD"), LocalDate.of(2026, 10, 3), null, TransactionStatus.PENDING,
            null, null, false, null, null));

    @BeforeEach
    void bensHistory() {
        when(history.historyFor(BEN.memberId())).thenReturn(entries);
        when(profiles.view(BEN.memberId())).thenReturn(new ProfileRules.Profile("Ben", DOUALA));
    }

    @Test
    void nobodySignedInIsSentToSignIn() throws Exception {                     // Auth.Login-2
        mvc.perform(get("/history")).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void theSignedInMemberSeesTheirOwnHistory() throws Exception {             // Ledger.History-1, -7
        mvc.perform(get("/history").with(oidcLogin().oidcUser(BEN)))
                .andExpect(status().isOk())
                .andExpect(view().name("history"))
                .andExpect(model().attribute("entries", entries))
                .andExpect(model().attribute("timeZone", DOUALA))
                .andExpect(content().string(containsString("You borrowed $20.00 from Ama")));
        verify(history).historyFor(BEN.memberId());
    }

    @Test
    void everyPageLinksToTheHistory() throws Exception {
        mvc.perform(get("/history").with(oidcLogin().oidcUser(BEN)))
                .andExpect(content().string(containsString("href=\"/history\"")));
    }
}
