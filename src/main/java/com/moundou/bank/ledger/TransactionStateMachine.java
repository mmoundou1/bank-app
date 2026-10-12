package com.moundou.bank.ledger;

import java.util.UUID;

public class TransactionStateMachine {
    enum Event { APPROVE, DECLINE, CANCEL }
    enum Role { INITIATOR, COUNTERPARTY, OUTSIDER }

    public sealed interface Transition {
        record AllowedTo(TransactionStatus to) implements Transition {}
        record WrongActor() implements Transition {}
        record NotPending() implements Transition {}
    }

    static Role roleOf(UUID actorId, LedgerTransaction item) {
        if(!(item.creditor().equals(actorId) || item.debtor().equals(actorId)))
            return Role.OUTSIDER;
        else if(item.initiatedBy().equals(actorId))
            return Role.INITIATOR;
        else
            return Role.COUNTERPARTY;
    }

    static Transition next(TransactionStatus from, Event event, Role role) {
       if(((event == Event.APPROVE || event == Event.DECLINE) && role != Role.COUNTERPARTY) ||
                                                                        (event == Event.CANCEL && role != Role.INITIATOR))
           return new Transition.WrongActor();

       if(from != TransactionStatus.PENDING)
           return new Transition.NotPending();

       if(event == Event.APPROVE)
           return  new Transition.AllowedTo(TransactionStatus.APPROVED);
       else if(event == Event.DECLINE)
           return new Transition.AllowedTo(TransactionStatus.DECLINED);
       else
           return new Transition.AllowedTo(TransactionStatus.CANCELLED);
    }
}
