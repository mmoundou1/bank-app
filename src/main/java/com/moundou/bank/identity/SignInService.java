package com.moundou.bank.identity;

import java.time.ZoneId;

/**
 * Decides whether a person Google has just identified may enter, and who they are
 * here (Technical Design 6; SRS Auth.Login-5, Auth.Roles-2, Auth.Roles-9).
 *
 * <h2>MB-9 exercise: this method is yours to write</h2>
 * Everything around it is built: Spring Security has already checked Google's
 * signature before this is called, and {@code GoogleSignIn} turns your answer into
 * a session or a refusal page. The tests are in {@code SignInServiceTests}; remove
 * its {@code @Disabled} line, run it, and make every test pass.
 *
 * The checks, in this order (Technical Design 6):
 * <ol>
 *   <li>Google has verified the email address. An unverified address proves nothing
 *       about who is signing in, so it is refused before anything else is looked at.</li>
 *   <li>The address is on the allow-list ({@link AllowList}). The schema stores
 *       addresses in lower case (MB-7, {@code member_email_lower}); Google may not send
 *       them that way.</li>
 *   <li>Find the member by their Google subject ({@link MemberAccounts#findBySubject}).
 *       If there is none, this is their first sign-in: create them as an ordinary
 *       family member, in {@code defaultTimeZone}, with Google's name as their display
 *       name (or, if Google sent no name, the part of the address before the @), and
 *       with no administrator step (Auth.Roles-2).</li>
 *   <li>The member is not deactivated (Auth.Login-5).</li>
 * </ol>
 * A refusal must never create a member.
 */
public class SignInService {

    private final AllowList allowList;
    private final MemberAccounts members;
    private final ZoneId defaultTimeZone;

    public SignInService(AllowList allowList, MemberAccounts members, ZoneId defaultTimeZone) {
        this.allowList = allowList;
        this.members = members;
        this.defaultTimeZone = defaultTimeZone;
    }

    public SignInResult signIn(GoogleIdentity identity) {
        throw new UnsupportedOperationException("MB-9 exercise: not written yet");
    }
}
