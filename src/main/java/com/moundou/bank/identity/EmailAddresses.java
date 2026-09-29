package com.moundou.bank.identity;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How email addresses are stored: trimmed and lower-cased (the schema refuses anything
 * else, {@code member_email_lower} and {@code allowed_email_lower}).
 *
 * The check is deliberately loose: something@something.something. An address only has
 * to match a Google account at sign-in, and Google is the real validator; this just
 * catches typing mistakes before they go on the allow-list.
 */
public final class EmailAddresses {

    private static final Pattern SHAPE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private EmailAddresses() { }

    /** The address as stored, or empty if it cannot be an email address. */
    public static Optional<String> normalize(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String email = typed.strip().toLowerCase(Locale.ROOT);
        return SHAPE.matcher(email).matches() ? Optional.of(email) : Optional.empty();
    }
}
