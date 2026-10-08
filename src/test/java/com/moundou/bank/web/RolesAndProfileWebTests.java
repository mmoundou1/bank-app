package com.moundou.bank.web;

import com.moundou.bank.NotPermittedException;
import com.moundou.bank.i18n.Formats;
import com.moundou.bank.identity.AdminRuleException;
import com.moundou.bank.identity.AdminService;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.MemberAdministration;
import com.moundou.bank.identity.ProfileRules;
import com.moundou.bank.identity.ProfileService;
import com.moundou.bank.identity.Role;
import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.AuditService;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.TransactionKind;
import com.moundou.bank.ledger.TransactionStatus;
import com.moundou.bank.ledger.DualConfirmationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
 * Fast suite, no database. The MB-10 screens: who can reach them, that the member acted
 * for is always the signed-in one, and that the pages render their text. Services are
 * stand-ins; their rules are tested against Postgres in RolesAndProfileIT.
 */
@WebMvcTest({AdminController.class, ProfileController.class, SignInController.class})
@Import({SecurityConfig.class, Formats.class})
class RolesAndProfileWebTests {

    static final UUID ADMIN_ID = UUID.randomUUID();
    static final UUID MEMBER_ID = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockBean GoogleSignIn googleSignIn;
    @MockBean AdminService admin;
    @MockBean AuditService audit;
    @MockBean ProfileService profiles;
    @MockBean MemberAdministration administration;
    @MockBean DualConfirmationService approvals;   // home shows the approval queue (MB-12)

    static SignedInMember signedIn(UUID id, String name, Role role) {
        OidcIdToken token = OidcIdToken.withTokenValue("t").subject("sub-" + id)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new SignedInMember(List.of(new SimpleGrantedAuthority("ROLE_" + role.name())), token, null, id, name);
    }

    static final SignedInMember ADMIN = signedIn(ADMIN_ID, "Boss", Role.FAMILY_ADMINISTRATOR);
    static final SignedInMember MEMBER = signedIn(MEMBER_ID, "Ama", Role.FAMILY_MEMBER);

    static MemberAdministration.MemberSummary summary(UUID id, String name, Role role, boolean active) {
        return new MemberAdministration.MemberSummary(id, name, name.toLowerCase() + "@example.com", role, active, ZoneId.of("Africa/Douala"));
    }

    // ---- reaching the administration pages (Auth.Roles-5, T-Roles-5a) ----------------

    @Test
    void anOrdinaryMemberIsForbiddenEveryAdministrationRoute() throws Exception {
        mvc.perform(get("/admin").with(oidcLogin().oidcUser(MEMBER))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit").param("a", UUID.randomUUID().toString()).param("b", UUID.randomUUID().toString())
                .with(oidcLogin().oidcUser(MEMBER))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/allow-list").param("email", "x@example.com").with(oidcLogin().oidcUser(MEMBER)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/members/" + UUID.randomUUID() + "/deactivate").with(oidcLogin().oidcUser(MEMBER)).with(csrf()))
                .andExpect(status().isForbidden());
        verify(admin, never()).allow(any(), anyString());
        verify(admin, never()).deactivate(any(), any());
    }

    @Test
    void aServiceRefusalIsA403EvenIfTheSessionClaimsTheRole() throws Exception {
        when(admin.overview(ADMIN_ID)).thenThrow(new NotPermittedException(ADMIN_ID, "view"));   // demoted since sign-in
        mvc.perform(get("/admin").with(oidcLogin().oidcUser(ADMIN))).andExpect(status().isForbidden());
    }

    @Test
    void theAdministratorSeesMembersAndTheAllowList() throws Exception {
        when(admin.overview(ADMIN_ID)).thenReturn(new AdminService.Overview(
                List.of(summary(ADMIN_ID, "Boss", Role.FAMILY_ADMINISTRATOR, true), summary(MEMBER_ID, "Ama", Role.FAMILY_MEMBER, false)),
                List.of(new MemberAdministration.AllowedEmail("ama@example.com", Instant.now(), "Boss"))));

        mvc.perform(get("/admin").with(oidcLogin().oidcUser(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Family administration"), containsString("ama@example.com"),
                        containsString("Added by Boss"), containsString("Family administrator"),
                        containsString("Deactivated"), containsString("(you)"))));
    }

    // ---- actions act as the signed-in administrator ---------------------------------

    @Test
    void addingAnAddressUsesTheSessionsMemberAndReportsIt() throws Exception {
        when(admin.allow(ADMIN_ID, "New@Example.com")).thenReturn("new@example.com");
        mvc.perform(post("/admin/allow-list").param("email", "New@Example.com").with(oidcLogin().oidcUser(ADMIN)).with(csrf()))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("messageKey", "admin.allowList.added"))
                .andExpect(flash().attribute("messageArg", "new@example.com"));
    }

    @Test
    void theLastAdministratorRuleIsShownNotSwallowed() throws Exception {   // Auth.Roles-12
        doThrow(new AdminRuleException("admin.lastAdministrator")).when(admin).deactivate(ADMIN_ID, ADMIN_ID);
        mvc.perform(post("/admin/members/" + ADMIN_ID + "/deactivate").with(oidcLogin().oidcUser(ADMIN)).with(csrf()))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("errorKey", "admin.lastAdministrator"));
    }

    @Test
    void everyChangeNeedsACsrfToken() throws Exception {
        mvc.perform(post("/admin/allow-list").param("email", "x@example.com").with(oidcLogin().oidcUser(ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/profile").param("displayName", "X").param("timeZone", "Europe/Paris").with(oidcLogin().oidcUser(MEMBER)))
                .andExpect(status().isForbidden());
    }

    // ---- audit view (Auth.Roles-10, USE-3) ------------------------------------------

    @Test
    void theAuditViewStatesWhoOwesWhomInWords() throws Exception {
        UUID ben = UUID.randomUUID();
        var a = summary(MEMBER_ID, "Ama", Role.FAMILY_MEMBER, true);
        var b = summary(ben, "Ben", Role.FAMILY_MEMBER, true);
        Currency usd = Currency.getInstance("USD");
        var loan = new LedgerTransaction(UUID.randomUUID(), TransactionKind.LOAN, MEMBER_ID, ben, 7500, usd,
                LocalDate.of(2026, 9, 1), "dinner", TransactionStatus.APPROVED, null, MEMBER_ID, Instant.now(), Instant.now(),
                null, null, false, UUID.randomUUID());
        when(audit.audit(ADMIN_ID, MEMBER_ID, ben)).thenReturn(Optional.of(
                new AuditService.AuditView(a, b, List.of(loan), Map.of(usd, 7500L))));

        mvc.perform(get("/admin/audit").param("a", MEMBER_ID.toString()).param("b", ben.toString()).with(oidcLogin().oidcUser(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Between Ama and Ben"), containsString("Ben owes Ama $75.00"),
                        containsString("Ama lent to Ben"), containsString("Approved"), containsString("Sep 1, 2026"))));
    }

    @Test
    void choosingTheSameMemberTwiceGoesBackWithAMessage() throws Exception {
        when(audit.audit(eq(ADMIN_ID), any(), any())).thenReturn(Optional.empty());
        mvc.perform(get("/admin/audit").param("a", MEMBER_ID.toString()).param("b", MEMBER_ID.toString()).with(oidcLogin().oidcUser(ADMIN)))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("errorKey", "admin.audit.chooseTwo"));
    }

    // ---- profile (Auth.Roles-11) ----------------------------------------------------

    @Test
    void theProfilePageShowsTheMembersOwnValues() throws Exception {
        when(profiles.view(MEMBER_ID)).thenReturn(new ProfileRules.Profile("Ama", ZoneId.of("Africa/Douala")));
        mvc.perform(get("/profile").with(oidcLogin().oidcUser(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("value=\"Ama\""),
                        containsString("<option value=\"Africa/Douala\" selected=\"selected\">"))));
    }

    @Test
    void savingAProfileAlwaysActsOnTheSignedInMember() throws Exception {   // T-Roles-11b, web half
        when(profiles.update(MEMBER_ID, "Ama M.", "Europe/Paris"))
                .thenReturn(new ProfileService.Outcome.Saved(new ProfileRules.Profile("Ama M.", ZoneId.of("Europe/Paris"))));
        mvc.perform(post("/profile").param("displayName", "Ama M.").param("timeZone", "Europe/Paris")
                        .param("memberId", ADMIN_ID.toString())   // an attempt to aim at someone else is simply ignored
                        .with(oidcLogin().oidcUser(MEMBER)).with(csrf()))
                .andExpect(redirectedUrl("/profile"))
                .andExpect(flash().attribute("messageKey", "profile.saved"));
        verify(profiles).update(MEMBER_ID, "Ama M.", "Europe/Paris");
        verify(profiles, never()).update(eq(ADMIN_ID), any(), any());
    }

    @Test
    void anInvalidProfileKeepsWhatWasTypedAndSaysWhy() throws Exception {   // UI-4
        when(profiles.update(MEMBER_ID, "", "Europe/Paris"))
                .thenReturn(new ProfileService.Outcome.Rejected(Map.of("displayName", "profile.displayName.required")));
        mvc.perform(post("/profile").param("displayName", "").param("timeZone", "Europe/Paris")
                        .with(oidcLogin().oidcUser(MEMBER)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Enter your name"),
                        containsString("<option value=\"Europe/Paris\" selected=\"selected\">"))));
    }

    // ---- home and navigation --------------------------------------------------------

    @Test
    void homeShowsTheCurrentNameAndOnlyAdministratorsSeeTheAdministrationLink() throws Exception {
        when(administration.findById(MEMBER_ID)).thenReturn(Optional.of(summary(MEMBER_ID, "Ama the Renamed", Role.FAMILY_MEMBER, true)));
        when(administration.findById(ADMIN_ID)).thenReturn(Optional.of(summary(ADMIN_ID, "Boss", Role.FAMILY_ADMINISTRATOR, true)));
        when(approvals.queue(any())).thenReturn(new DualConfirmationService.TransactionQueue(List.of(), List.of(), Map.of()));

        mvc.perform(get("/").with(oidcLogin().oidcUser(MEMBER)))
                .andExpect(content().string(allOf(containsString("Signed in as Ama the Renamed"),
                        containsString("Your profile"), not(containsString("href=\"/admin\"")))));
        mvc.perform(get("/").with(oidcLogin().oidcUser(ADMIN)))
                .andExpect(content().string(containsString("href=\"/admin\"")));
    }
}
