package com.moundou.bank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * The acting member may not do this: unknown, deactivated (Auth.Login-5), or lacking
 * the role (Auth.Roles-5). Authorization lives in the services (Technical Design 6), so
 * every service throws this, and the web layer answers 403 Forbidden.
 *
 * Shared by every module, which is why it sits at the top of the package tree rather
 * than inside one of them.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class NotPermittedException extends RuntimeException {

    public NotPermittedException(UUID memberId, String action) {
        super("Member " + memberId + " may not " + action);
    }
}
