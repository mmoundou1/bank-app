package com.moundou.bank.web;

import com.moundou.bank.health.HealthController;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.identity.MemberAdministration;
import com.moundou.bank.identity.Role;
import com.moundou.bank.identity.SignedInMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite, no database. The outer wall of SRS 3.10: what is open, what needs a
 * signed-in member, and where refusals go. Google is never contacted: a signed-in
 * member is simulated with Spring Security's test support.
 */
@WebMvcTest({SignInController.class, HealthController.class})
@ExtendWith(OutputCaptureExtension.class)
@Import(SecurityConfig.class)
class SecurityConfigTests {

    @Autowired
    MockMvc mvc;

    @MockBean
    GoogleSignIn googleSignIn;   // never reached here: nobody actually signs in with Google

    @MockBean
    MemberAdministration administration;   // home reads the member's current name (MB-10)

    static SignedInMember ama() {
        OidcIdToken token = OidcIdToken.withTokenValue("token").subject("sub-ama")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new SignedInMember(List.of(new SimpleGrantedAuthority("ROLE_FAMILY_MEMBER")),
                token, null, UUID.randomUUID(), "Ama");
    }

    @Test
    void theKeepAlivePingNeedsNoSignIn() throws Exception {   // ADR-011
        mvc.perform(get("/healthz")).andExpect(status().isOk());
    }

    /** T-Login-2a: no page is served without a signed-in member; the browser is sent to sign in. */
    @Test
    void everythingElseSendsAStrangerToTheSignInPage() throws Exception {
        for (String path : List.of("/", "/transactions/new", "/history", "/pending/" + UUID.randomUUID(), "/admin")) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        }
    }

    @Test
    void aPostWithoutASignedInMemberIsRejected() throws Exception {
        mvc.perform(post("/transactions").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/transactions")).andExpect(status().isForbidden());   // and without a CSRF token, outright
    }

    /** T-Login-3a: there is no way to register. Every such path is just "sign in first". */
    @Test
    void thereIsNoRegistrationRoute() throws Exception {
        for (String path : List.of("/register", "/signup", "/sign-up", "/users/new")) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection());
        }
    }

    @Test
    void theSignInPageOffersGoogle() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/google")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in with Google")));
    }

    /** Auth.Login-5: the deactivated member is told why. */
    @Test
    void aRefusedSignInExplainsWhy() throws Exception {
        mvc.perform(get("/login").param("refused", "deactivated"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("This account is no longer active.")));
        mvc.perform(get("/login").param("refused", "not_on_allow_list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("not on the family")));
        mvc.perform(get("/login").param("refused", "<script>"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("did not work")));
    }

    @Test
    void aSignedInMemberReachesHome() throws Exception {
        SignedInMember ama = ama();
        org.mockito.Mockito.when(administration.findById(ama.memberId())).thenReturn(java.util.Optional.of(
                new MemberAdministration.MemberSummary(ama.memberId(), "Ama", "ama@example.com", Role.FAMILY_MEMBER, true,
                        java.time.ZoneId.of("Africa/Douala"))));
        mvc.perform(get("/").with(oidcLogin().oidcUser(ama)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Signed in as Ama")));
    }

    @Test
    void signingOutEndsTheSession() throws Exception {
        mvc.perform(post("/logout").with(oidcLogin().oidcUser(ama())).with(csrf()))
                .andExpect(redirectedUrl("/login?signedOut"));
    }

    @Test
    void theRefusalHandlerCarriesTheReasonToTheSignInPage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityConfig.refusalHandler().onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error(GoogleSignIn.REFUSAL_PREFIX + "deactivated")));
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?refused=deactivated");

        MockHttpServletResponse other = new MockHttpServletResponse();
        SecurityConfig.refusalHandler().onAuthenticationFailure(new MockHttpServletRequest(), other,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token")));
        assertThat(other.getRedirectedUrl()).isEqualTo("/login?failed");
    }

    /** SEC-5: every failed sign-in is logged with its reason, and no token. */
    @Test
    void failedSignInsAreLoggedWithTheirReason(CapturedOutput output) throws Exception {
        SecurityConfig.refusalHandler().onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token_response",
                        "An error occurred while attempting to retrieve the OAuth 2.0 Access Token Response", null)));
        SecurityConfig.refusalHandler().onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new OAuth2AuthenticationException(new OAuth2Error(GoogleSignIn.REFUSAL_PREFIX + "not_on_allow_list")));

        assertThat(output).contains("Sign-in with Google failed: [invalid_token_response] An error occurred");
        assertThat(output).contains("Sign-in refused: not_on_allow_list");
    }
}
