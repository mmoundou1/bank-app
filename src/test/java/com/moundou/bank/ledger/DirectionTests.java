package com.moundou.bank.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. Model 6 DT-2: the direction label of a balance comes from its sign alone,
 * read from the viewer's side (positive means owed to the viewer, Technical Design 14.2).
 * The words ("Owed to you", "Ben owes you") are the template's (I18N-1).
 */
class DirectionTests {

    @Test
    void aPositiveBalanceIsOwedToYou() {                                         // T-Use-3a
        assertThat(Direction.of(1)).isEqualTo(Direction.OWED_TO_YOU);
        assertThat(Direction.of(1_000_000_000L)).isEqualTo(Direction.OWED_TO_YOU);
    }

    @Test
    void aNegativeBalanceIsOneYouOwe() {                                         // T-Use-3a
        assertThat(Direction.of(-1)).isEqualTo(Direction.YOU_OWE);
        assertThat(Direction.of(-1_000_000_000L)).isEqualTo(Direction.YOU_OWE);
    }

    @Test
    void zeroIsEven() {                                                          // T-Cpty-3a
        assertThat(Direction.of(0)).isEqualTo(Direction.EVEN);
    }
}