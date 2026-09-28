package com.moundou.bank.identity;

/** The access allow-list (SRS 4.2 AllowedEmail). Presence is the sole gate on access (Auth.Roles-9). */
public interface AllowList {

    /** @param email lower-case, as stored */
    boolean contains(String email);
}
