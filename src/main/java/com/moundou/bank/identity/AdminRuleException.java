package com.moundou.bank.identity;

/**
 * An administrative action refused by a rule, not by authorization: the actor may
 * administer, but not this. Carries the message key the page shows (I18N-1).
 */
public class AdminRuleException extends RuntimeException {

    private final String messageKey;

    public AdminRuleException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
