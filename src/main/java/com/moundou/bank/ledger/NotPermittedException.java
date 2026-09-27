package com.moundou.bank.ledger;

import java.util.UUID;

/**
 * The acting member may not do this at all: unknown or deactivated (Auth.Login-5).
 * Authorization lives in the services (Technical Design 6); the web layer maps this
 * to 403.
 */
public class NotPermittedException extends RuntimeException {

    public NotPermittedException(UUID memberId, String action) {
        super("Member " + memberId + " may not " + action);
    }
}
