package com.moundou.bank;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. Proves CON-3 on real Postgres: a multi-statement transaction
 * commits atomically and rolls back cleanly.
 *
 * This is the constraint the entire platform choice in ADR-007 rests on, and
 * the guarantee Ledger.Approval-4 and Ledger.Approval-5 are built on top of.
 * MB-6 asks for it to be proven rather than assumed - this is that proof, and
 * it keeps holding every time CI runs.
 */
@Testcontainers
class AtomicTransactionIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return new JdbcTemplate(ds);
    }

    @Test
    void multiStatementTransactionRollsBackEntirely() throws Exception {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE TABLE IF NOT EXISTS balance (id INT PRIMARY KEY, amount_minor BIGINT NOT NULL)");
        jdbc.update("DELETE FROM balance");
        jdbc.update("INSERT INTO balance (id, amount_minor) VALUES (1, 1000), (2, 1000)");

        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var debit = conn.prepareStatement("UPDATE balance SET amount_minor = amount_minor - 500 WHERE id = 1");
                 var credit = conn.prepareStatement("UPDATE balance SET amount_minor = amount_minor + 500 WHERE id = 99")) {
                debit.executeUpdate();
                credit.executeUpdate();   // affects no rows - the second leg silently does nothing
                conn.rollback();          // stands in for a failure between the two legs
            }
        }

        Long first = jdbc.queryForObject("SELECT amount_minor FROM balance WHERE id = 1", Long.class);
        Long second = jdbc.queryForObject("SELECT amount_minor FROM balance WHERE id = 2", Long.class);

        // Neither leg survived. A partial debit would be the worst failure this product has.
        assertThat(first).isEqualTo(1000L);
        assertThat(second).isEqualTo(1000L);
    }

    @Test
    void committedTransactionPersistsBothLegs() throws Exception {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE TABLE IF NOT EXISTS balance (id INT PRIMARY KEY, amount_minor BIGINT NOT NULL)");
        jdbc.update("DELETE FROM balance");
        jdbc.update("INSERT INTO balance (id, amount_minor) VALUES (1, 1000), (2, 1000)");

        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            conn.prepareStatement("UPDATE balance SET amount_minor = amount_minor - 500 WHERE id = 1").executeUpdate();
            conn.prepareStatement("UPDATE balance SET amount_minor = amount_minor + 500 WHERE id = 2").executeUpdate();
            conn.commit();
        }

        Long first = jdbc.queryForObject("SELECT amount_minor FROM balance WHERE id = 1", Long.class);
        Long second = jdbc.queryForObject("SELECT amount_minor FROM balance WHERE id = 2", Long.class);

        assertThat(first).isEqualTo(500L);
        assertThat(second).isEqualTo(1500L);
        // Zero-sum holds - the seed of Dashboard.Pool-4 / INT-3.
        assertThat(first + second).isEqualTo(2000L);
    }
}
