package com.moundou.bank.identity;

/**
 * What Google told us about the person signing in, taken from the verified ID token
 * (ADR-006). Plain data: by the time this exists, Spring Security has already checked
 * that the token really came from Google, is meant for this app, and has not expired.
 *
 * @param subject       Google's permanent identifier for this person (the {@code sub} claim).
 *                      It never changes, even if the person changes their email address.
 * @param email         the email address on the Google account, as Google sent it
 * @param emailVerified whether Google has verified that the person controls that address.
 *                      A {@code Boolean}, not a {@code boolean}: Google may leave the claim
 *                      out, and then this is {@code null}.
 * @param fullName      the name on the Google account; may be {@code null}
 */
public record GoogleIdentity(String subject, String email, Boolean emailVerified, String fullName) {
}
