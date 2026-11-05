package com.moundou.bank.notification;

import com.moundou.bank.ledger.TransactionStatus;

public class AlertRules {
    public enum Decision {
        SEND,
        SKIP
    }

    public static Decision decide(OutboxWriter.AlertKind kind, TransactionStatus status) {

        if(kind == OutboxWriter.AlertKind.PENDING_ALERT) {
            if(status == TransactionStatus.APPROVED || status == TransactionStatus.DECLINED
                                                                            || status == TransactionStatus.CANCELLED)
                return Decision.SKIP;
        }

        return Decision.SEND;

    }

}
