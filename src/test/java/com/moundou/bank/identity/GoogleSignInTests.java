package com.moundou.bank.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast suite. The adapter between Spring Security and {@link SignInService}: it passes
 * the right facts from Google's token, and turns the answer into a session principal
 * or a refusal. The sign-in decision itself is stubbed; that is SignInServiceTests.
 */
class GoogleSignInTests {

    static OidcUserRequest requestFor(OidcIdToken idToken) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId("client").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.example/auth").tokenUri("https://accounts.example/token")
                .build();
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access",
                Instant.now(), Instant.now().plusSeconds(60));
        return new OidcUserRequest(google, access, idToken);
    }

    static OidcIdToken token(Boolean emailVerified) {
        OidcIdToken.Builder builder = OidcIdToken.withTokenValue("id-token").subject("sub-ama")
                .claim("email", "Ama@Example.com").claim("name", "Ama Moundou")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (emailVerified != null) {
            builder.claim("email_verified", emailVerified);
        }
        return builder.build();
    }

    /** Stands in for Spring's OidcUserService, which would call Google's userinfo endpoint. */
    static GoogleSignIn adapter(SignInService decision) {
        return new GoogleSignIn(decision, request -> new DefaultOidcUser(List.of(), request.getIdToken()));
    }

    /** A SignInService whose answer is fixed, and which records what it was asked. */
    static SignInService answering(SignInResult answer, AtomicReference<GoogleIdentity> asked) {
        return new SignInService(null, null, ZoneId.of("UTC")) {
            @Override
            public SignInResult signIn(GoogleIdentity identity) {
                asked.set(identity);
                return answer;
            }
        };
    }

    @Test
    void googlesClaimsArePassedOnAsSent() {
        AtomicReference<GoogleIdentity> asked = new AtomicReference<>();
        MemberAccount ama = new MemberAccount(UUID.randomUUID(), "Ama", ZoneId.of("Africa/Douala"), Role.FAMILY_MEMBER, true);

        adapter(answering(new SignInResult.SignedIn(ama), asked)).loadUser(requestFor(token(true)));

        // Passed on exactly as Google sent them; lower-casing is SignInService's job.
        assertThat(asked.get()).isEqualTo(new GoogleIdentity("sub-ama", "Ama@Example.com", true, "Ama Moundou"));
    }

    @Test
    void aMissingVerificationClaimArrivesAsNull() {
        AtomicReference<GoogleIdentity> asked = new AtomicReference<>();
        GoogleSignIn adapter = adapter(answering(new SignInResult.Refused(SignInResult.Reason.EMAIL_NOT_VERIFIED), asked));

        assertThatThrownBy(() -> adapter.loadUser(requestFor(token(null))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(asked.get().emailVerified()).isNull();
    }

    @Test
    void signedInBecomesASessionPrincipalCarryingTheMemberAndRole() {
        MemberAccount admin = new MemberAccount(UUID.randomUUID(), "Ama", ZoneId.of("Africa/Douala"),
                Role.FAMILY_ADMINISTRATOR, true);

        var principal = adapter(answering(new SignInResult.SignedIn(admin), new AtomicReference<>()))
                .loadUser(requestFor(token(true)));

        assertThat(principal).isInstanceOf(SignedInMember.class);
        assertThat(((SignedInMember) principal).memberId()).isEqualTo(admin.id());
        assertThat(((SignedInMember) principal).displayName()).isEqualTo("Ama");
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_FAMILY_ADMINISTRATOR");
    }

    @Test
    void aRefusalAbandonsTheSignInWithTheReason() {
        GoogleSignIn adapter = adapter(answering(new SignInResult.Refused(SignInResult.Reason.DEACTIVATED), new AtomicReference<>()));

        assertThatThrownBy(() -> adapter.loadUser(requestFor(token(true))))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, e ->
                        assertThat(e.getError().getErrorCode()).isEqualTo("moundou_refused_deactivated"));
    }
}
