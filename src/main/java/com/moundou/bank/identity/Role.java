package com.moundou.bank.identity;

/** Exactly one per account (Auth.Roles-1). Mirrors {@code member_role_check}. */
public enum Role {
    FAMILY_MEMBER, FAMILY_ADMINISTRATOR;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static Role fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
