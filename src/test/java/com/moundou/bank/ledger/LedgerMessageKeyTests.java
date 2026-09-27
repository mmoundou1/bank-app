package com.moundou.bank.ledger;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. Every message key the ledger's rules can return exists in
 * messages.properties (I18N-1). Without this, a mistyped key would only show up
 * as a raw key on a real screen.
 */
class LedgerMessageKeyTests {

    @Test
    void everyKeyTheRulesCanReturnIsInTheBundle() throws Exception {
        Properties bundle = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/messages.properties")) {
            bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> keys = new ArrayList<>();
        for (Class<?> type : List.of(Amounts.class, EntryValidator.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    if (value.startsWith("ledger.")) {
                        keys.add(value);
                    }
                }
            }
        }
        assertThat(keys).as("keys found by reflection").hasSizeGreaterThanOrEqualTo(12);
        assertThat(keys).allSatisfy(key -> assertThat(bundle).as(key).containsKey(key));
    }
}
