package com.moundou.bank.identity;

import java.time.ZoneId;

/**
 * Everything needed to create a member on their first sign-in (Auth.Roles-2).
 * The id and creation time are filled in when the row is written.
 *
 * @param email lower-case; the schema refuses anything else ({@code member_email_lower})
 */
public record NewMemberAccount(String subject, String email, String displayName, ZoneId timeZone, Role role) {
}
