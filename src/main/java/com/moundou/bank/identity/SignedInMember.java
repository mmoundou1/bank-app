package com.moundou.bank.identity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.io.Serial;
import java.util.Collection;
import java.util.UUID;

/**
 * Who is signed in, as stored in the session. Google's identity plus this app's member
 * id, which is what every service needs as "the acting member" (Technical Design 6).
 *
 * It is written into the {@code spring_session_attributes} table, so it must stay
 * serializable. It is a snapshot taken at sign-in: services still check the member is
 * active on every action, so deactivation takes effect before the session expires.
 */
public class SignedInMember extends DefaultOidcUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID memberId;
    private final String displayName;

    public SignedInMember(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken,
                          OidcUserInfo userInfo, UUID memberId, String displayName) {
        super(authorities, idToken, userInfo, "sub");
        this.memberId = memberId;
        this.displayName = displayName;
    }

    public UUID memberId() {
        return memberId;
    }

    public String displayName() {
        return displayName;
    }
}
