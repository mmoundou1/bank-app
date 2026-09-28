package com.moundou.bank.identity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Where Spring Security's sign-in hands over to this app (the "your four checks" box
 * in the MB-9 walkthrough).
 *
 * By the time {@link #loadUser} runs, Spring has exchanged Google's one-time code for
 * an ID token and checked that token's signature, audience and expiry. This class then:
 * <ol>
 *   <li>lets Spring's own {@link OidcUserService} build the Google user from the token;</li>
 *   <li>passes the parts that matter to {@link SignInService}, as plain data;</li>
 *   <li>turns the answer into either a {@link SignedInMember}, which Spring stores in
 *       the session, or an {@link OAuth2AuthenticationException}, which makes Spring
 *       abandon the sign-in and call the failure handler, so no session is created.</li>
 * </ol>
 */
@Component
public class GoogleSignIn implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** Prefix of the error codes this class raises, so the failure handler can tell them apart. */
    public static final String REFUSAL_PREFIX = "moundou_refused_";

    private final SignInService signIn;
    private final OAuth2UserService<OidcUserRequest, OidcUser> google;

    /** The constructor Spring uses. Marked, because a class with two constructors leaves Spring unable to choose. */
    @Autowired
    public GoogleSignIn(SignInService signIn) {
        this(signIn, new OidcUserService());
    }

    /** Lets tests stand in for Spring's service, which would otherwise call Google. */
    GoogleSignIn(SignInService signIn, OAuth2UserService<OidcUserRequest, OidcUser> google) {
        this.signIn = signIn;
        this.google = google;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = google.loadUser(request);

        SignInResult result = signIn.signIn(new GoogleIdentity(
                user.getSubject(), user.getEmail(), user.getEmailVerified(), user.getFullName()));

        return switch (result) {
            case SignInResult.SignedIn signedIn -> {
                MemberAccount member = signedIn.member();
                yield new SignedInMember(
                        List.of(new SimpleGrantedAuthority("ROLE_" + member.role().name())),
                        user.getIdToken(), user.getUserInfo(), member.id(), member.displayName());
            }
            case SignInResult.Refused refused -> throw new OAuth2AuthenticationException(
                    new OAuth2Error(REFUSAL_PREFIX + refused.reason().name().toLowerCase()),
                    "Sign-in refused: " + refused.reason());
        };
    }
}
