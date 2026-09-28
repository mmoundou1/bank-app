package com.moundou.bank.identity;

import java.util.Optional;

/** Finding and creating members for sign-in. */
public interface MemberAccounts {

    /** The member whose Google subject this is, if they have signed in before. */
    Optional<MemberAccount> findBySubject(String subject);

    /** Creates the member, active, and returns them with their new id. */
    MemberAccount create(NewMemberAccount account);
}
