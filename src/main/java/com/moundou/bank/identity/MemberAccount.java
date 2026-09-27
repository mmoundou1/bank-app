package com.moundou.bank.identity;

import java.time.ZoneId;
import java.util.UUID;

/**
 * A member as the identity module sees it: the ledger's {@link Member} plus the role.
 */
public record MemberAccount(UUID id, String displayName, ZoneId timeZone, Role role, boolean active) {

    public Member asMember() {
        return new Member(id, displayName, timeZone, active);
    }
}
