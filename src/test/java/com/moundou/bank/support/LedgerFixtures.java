package com.moundou.bank.support;

import com.moundou.bank.ledger.NewTransaction;
import com.moundou.bank.ledger.TransactionKind;
import com.moundou.bank.ledger.TransactionRepository;
import com.moundou.bank.ledger.TransactionStatus;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Builds ledger rows for the slow suite, following the direction convention of
 * SRS 4.2 (CR-14): creditor gains a claim on debtor.
 *
 * Rows go in through {@link TransactionRepository}, the same statements the application
 * runs. Approving a fixture calls {@code recordDecision} directly, without the loan lock;
 * that is fine for setting up history, and the tests that are about the lock take it
 * themselves.
 */
public final class LedgerFixtures {

    public static final Currency USD = Currency.getInstance("USD");
    public static final Currency XAF = Currency.getInstance("XAF");
    public static final Currency EUR = Currency.getInstance("EUR");
    public static final LocalDate DATE = LocalDate.of(2026, 9, 1);

    private final JdbcClient jdbc;
    private final TransactionRepository repository;

    public LedgerFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.repository = new TransactionRepository(jdbc);
    }

    public TransactionRepository repository() {
        return repository;
    }

    public UUID member(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO member (member_id, idp_subject, email, display_name, time_zone, role)
                VALUES (:id, :subject, :email, :name, 'Africa/Douala', 'family_member')
                """)
                .param("id", id)
                .param("subject", "google-" + id)
                .param("email", name.toLowerCase() + "-" + id + "@example.com")
                .param("name", name)
                .update();
        return id;
    }

    // ---- pending rows ---------------------------------------------------------------

    /** A pending loan: {@code lender} is creditor, {@code borrower} is debtor. */
    public UUID loan(UUID lender, UUID borrower, long amountMinor, Currency currency) {
        return insert(new NewTransaction(UUID.randomUUID(), TransactionKind.LOAN, lender, borrower,
                amountMinor, currency, DATE, null, lender, null, null, false, UUID.randomUUID()));
    }

    /** A pending repayment of {@code loanId}, recorded by the borrower: borrower -> lender. */
    public UUID repayment(UUID loanId) {
        var loan = repository.findById(loanId).orElseThrow();
        return repayment(loanId, loan.amountMinor(), loan.currency());
    }

    /** As {@link #repayment(UUID)}, with the currency given explicitly (to break INT-8). */
    public UUID repayment(UUID loanId, long amountMinor, Currency currency) {
        var loan = repository.findById(loanId).orElseThrow();
        UUID borrower = loan.debtor();
        UUID lender = loan.creditor();
        return insert(new NewTransaction(UUID.randomUUID(), TransactionKind.REPAYMENT, borrower, lender,
                amountMinor, currency, DATE, null, borrower, loanId, null, false, UUID.randomUUID()));
    }

    /** A pending compensating entry reversing {@code targetId}: its parties swapped. */
    public UUID correction(UUID targetId) {
        var target = repository.findById(targetId).orElseThrow();
        return correction(targetId, target.currency());
    }

    public UUID correction(UUID targetId, Currency currency) {
        var target = repository.findById(targetId).orElseThrow();
        return insert(new NewTransaction(UUID.randomUUID(), TransactionKind.COMPENSATING,
                target.debtor(), target.creditor(), target.amountMinor(), currency, DATE, null,
                target.debtor(), null, targetId, false, UUID.randomUUID()));
    }

    public UUID insert(NewTransaction t) {
        repository.insert(t);
        return t.id();
    }

    // ---- decisions ------------------------------------------------------------------

    public UUID approve(UUID id) {
        assertThat(repository.recordDecision(id, TransactionStatus.APPROVED, Instant.now(), null)).isEqualTo(1);
        return id;
    }

    public UUID decline(UUID id) {
        assertThat(repository.recordDecision(id, TransactionStatus.DECLINED, Instant.now(), "not mine")).isEqualTo(1);
        return id;
    }

    public UUID cancel(UUID id) {
        assertThat(repository.recordDecision(id, TransactionStatus.CANCELLED, Instant.now(), null)).isEqualTo(1);
        return id;
    }

    public UUID approvedLoan(UUID lender, UUID borrower, long amountMinor, Currency currency) {
        return approve(loan(lender, borrower, amountMinor, currency));
    }

    public TransactionStatus status(UUID id) {
        return repository.findById(id).orElseThrow().status();
    }

    // ---- assertions -----------------------------------------------------------------

    /**
     * Runs {@code action} and asserts that the database rejected it with the named
     * constraint. Asserting the name, not just "an error", is what stops a test passing
     * because some other rule happened to fire.
     */
    public static void assertRejectedBy(String constraint, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException e) {
            Throwable cause = e;
            while (cause != null && !(cause instanceof PSQLException)) {
                cause = cause.getCause();
            }
            assertThat(cause).as("root cause of %s", e).isInstanceOf(PSQLException.class);
            var server = ((PSQLException) cause).getServerErrorMessage();
            assertThat(server).isNotNull();
            assertThat(server.getConstraint())
                    .as("constraint that rejected the row (%s)", server.getMessage())
                    .isEqualTo(constraint);
            return;
        }
        fail("expected the database to reject this with " + constraint + ", but it was accepted");
    }
}
