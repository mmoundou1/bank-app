package com.moundou.bank.identity;

/**
 * The answer to "may this Google identity enter?".
 */
public sealed interface SignInResult {

    /** Signed in as this member, possibly created just now. */
    record SignedIn(MemberAccount member) implements SignInResult { }

    /** Not let in. No session is created, and no member is created. */
    record Refused(Reason reason) implements SignInResult { }

    enum Reason {
        /** Google has not verified the email address, so it proves nothing (Technical Design 6, check 1). */
        EMAIL_NOT_VERIFIED,
        /** The address is not on the allow-list (Auth.Roles-9, SEC-3). */
        NOT_ON_ALLOW_LIST,
        /** The member exists but has been deactivated (Auth.Login-5). */
        DEACTIVATED
    }
}
