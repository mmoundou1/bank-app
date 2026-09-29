package com.moundou.bank.i18n;

import com.moundou.bank.identity.Role;
import com.moundou.bank.ledger.TransactionKind;
import com.moundou.bank.ledger.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. Templates build some message keys at runtime from an enum value, such as
 * {@code 'status.' + t.status().dbValue()}. TemplateTextTests cannot see those, so this
 * checks every value of every such enum has its key (I18N-1).
 */
class ComputedMessageKeyTests {

    @Test
    void everyEnumValueShownOnAScreenHasAMessage() throws IOException {
        Properties bundle = SrsMessageTests.loadBundle();
        List<String> keys = new ArrayList<>();
        for (Role role : Role.values()) {
            keys.add("admin.role." + role.dbValue());
        }
        for (TransactionStatus status : TransactionStatus.values()) {
            keys.add("status." + status.dbValue());
        }
        for (TransactionKind kind : TransactionKind.values()) {
            keys.add("audit.kind." + kind.dbValue());
        }
        assertThat(keys).allSatisfy(key -> assertThat(bundle).as(key).containsKey(key));
    }
}
