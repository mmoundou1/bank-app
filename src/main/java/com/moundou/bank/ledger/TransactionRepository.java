package com.moundou.bank.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plain-SQL access to the {@code transaction} table (ADR-015). Every statement is
 * written out here so it can be reviewed, and the slow suite runs these same
 * statements against real Postgres.
 *
 * <h2>What this class is, and is not</h2>
 * It is the data access that the schema's integrity rules depend on: inserting a row,
 * locking a row, and deciding whether a loan is Outstanding. It holds no business
 * rules and no authorization. The services that call it (entry MB-11, approval MB-12,
 * repayment MB-13, history MB-14) own validation, the state machine and who may act.
 *
 * <h2>INT-5: why a lock and not a constraint</h2>
 * SRS 1.10 allows a loan at most one approved repayment <em>that has not been
 * reversed</em>. A reversed repayment stays approved forever in an append-only ledger,
 * so no unique index can express the rule. Instead, approving a repayment must, inside
 * one database transaction:
 * <ol>
 *   <li>{@link #lockForUpdate} the loan (the referenced row) first,</li>
 *   <li>then {@link #lockForUpdate} the repayment itself,</li>
 *   <li>check the repayment is still pending,</li>
 *   <li>check {@link #loanLifecycle} is {@link LoanLifecycle#OUTSTANDING},</li>
 *   <li>and only then {@link #recordDecision}.</li>
 * </ol>
 * Two concurrent approvals for the same loan now queue on step 1, and the second one
 * sees the first's committed approval in step 4 and is refused (T-Repay-8b,
 * Ledger.Repayment-10). The referenced row is always locked before the referencing one,
 * in every approval, so two approvals can never each hold the lock the other wants
 * (Technical Design 4).
 *
 * This relies on Postgres's default READ COMMITTED isolation, where every statement
 * sees everything committed before it started, including work committed while it was
 * waiting for the lock. Under REPEATABLE READ the check in step 4 would read a snapshot
 * taken before the wait and could approve a second repayment; do not raise the
 * isolation level on the approval transaction.
 *
 */
@Repository
public class TransactionRepository {

    private static final String COLUMNS = """
            transaction_id, kind, creditor_id, debtor_id, amount_minor, currency,
            transaction_date, note, status, decline_reason, initiated_by, created_at,
            decided_at, settles_transaction_id, corrects_transaction_id,
            is_opening_balance, submission_key""";

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a pending transaction. Status, created_at and decided_at take their
     * database defaults (pending, now(), null).
     *
     * A retried submission violates {@code transaction_submission_key}; the entry
     * service (MB-11) catches that and returns {@link #findBySubmissionKey}'s row as a
     * success (Ledger.Entry-14).
     */
    public void insert(NewTransaction t) {
        jdbc.sql("""
                INSERT INTO transaction (
                    transaction_id, kind, creditor_id, debtor_id, amount_minor, currency,
                    transaction_date, note, initiated_by, settles_transaction_id,
                    corrects_transaction_id, is_opening_balance, submission_key)
                VALUES (
                    :id, :kind, :creditor, :debtor, :amount, :currency,
                    :date, :note, :initiatedBy, :settles,
                    :corrects, :opening, :submissionKey)
                """)
                .param("id", t.id())
                .param("kind", t.kind().dbValue())
                .param("creditor", t.creditor())
                .param("debtor", t.debtor())
                .param("amount", t.amountMinor())
                .param("currency", t.currency().getCurrencyCode())
                .param("date", t.transactionDate())
                .param("note", t.note())
                .param("initiatedBy", t.initiatedBy())
                .param("settles", t.settlesTransactionId())
                .param("corrects", t.correctsTransactionId())
                .param("opening", t.openingBalance())
                .param("submissionKey", t.submissionKey())
                .update();
    }

    /**
     * Inserts a pending transaction unless this member has already submitted one with
     * the same submission key (Ledger.Entry-14, Technical Design D1).
     *
     * Returns true when the row was inserted, false when an earlier attempt already
     * created it; the caller then answers with {@link #findBySubmissionKey}.
     *
     * Why ON CONFLICT rather than catching the duplicate-key error: in Postgres a failed
     * statement aborts the whole transaction, so after a caught violation nothing more
     * could be done in it. ON CONFLICT turns the duplicate into an ordinary "0 rows".
     * It is also safe when two retries arrive together: the second waits for the first
     * to commit, then inserts nothing.
     */
    public boolean insertIfNew(NewTransaction t) {
        int inserted = jdbc.sql("""
                INSERT INTO transaction (
                    transaction_id, kind, creditor_id, debtor_id, amount_minor, currency,
                    transaction_date, note, initiated_by, settles_transaction_id,
                    corrects_transaction_id, is_opening_balance, submission_key)
                VALUES (
                    :id, :kind, :creditor, :debtor, :amount, :currency,
                    :date, :note, :initiatedBy, :settles,
                    :corrects, :opening, :submissionKey)
                ON CONFLICT ON CONSTRAINT transaction_submission_key DO NOTHING
                """)
                .param("id", t.id())
                .param("kind", t.kind().dbValue())
                .param("creditor", t.creditor())
                .param("debtor", t.debtor())
                .param("amount", t.amountMinor())
                .param("currency", t.currency().getCurrencyCode())
                .param("date", t.transactionDate())
                .param("note", t.note())
                .param("initiatedBy", t.initiatedBy())
                .param("settles", t.settlesTransactionId())
                .param("corrects", t.correctsTransactionId())
                .param("opening", t.openingBalance())
                .param("submissionKey", t.submissionKey())
                .update();
        return inserted == 1;
    }

    /**
     * Appends one row to the transition log (Data-5). {@code from} is null for the row
     * that records creation as pending. Call it in the same database transaction as the
     * change it records.
     */
    public void recordHistory(UUID transactionId, TransactionStatus from, TransactionStatus to, UUID actorId) {
        jdbc.sql("""
                INSERT INTO transaction_status_history (history_id, transaction_id, from_status, to_status, actor_id)
                VALUES (:id, :transactionId, :from, :to, :actor)""")
                .param("id", UUID.randomUUID())
                .param("transactionId", transactionId)
                .param("from", from == null ? null : from.dbValue())
                .param("to", to.dbValue())
                .param("actor", actorId)
                .update();
    }

    public Optional<LedgerTransaction> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transaction WHERE transaction_id = :id")
                .param("id", id)
                .query(TransactionRepository::mapRow)
                .optional();
    }

    /** The record an earlier attempt of the same submission created (Ledger.Entry-14). */
    public Optional<LedgerTransaction> findBySubmissionKey(UUID initiatedBy, UUID submissionKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transaction"
                        + " WHERE initiated_by = :initiatedBy AND submission_key = :key")
                .param("initiatedBy", initiatedBy)
                .param("key", submissionKey)
                .query(TransactionRepository::mapRow)
                .optional();
    }

    /**
     * Every transaction between two members, in any status and either direction, newest
     * first. For the administrator's audit view only (Auth.Roles-10); the caller checks
     * the role. Member-facing reads are always filtered by the member (SEC-2).
     */
    public List<LedgerTransaction> findBetween(UUID a, UUID b) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transaction"
                        + " WHERE (creditor_id = :a AND debtor_id = :b) OR (creditor_id = :b AND debtor_id = :a)"
                        + " ORDER BY created_at DESC, transaction_id")
                .param("a", a).param("b", b)
                .query(TransactionRepository::mapRow).list();
    }

    /**
     * The approved balance between two members in each currency, from {@code a}'s side:
     * positive means {@code b} owes {@code a}. Read from the pair_net view (Technical
     * Design 3), which stores each pair once, from the lower member id's side.
     */
    public Map<Currency, Long> pairBalances(UUID a, UUID b) {
        Map<Currency, Long> balances = new TreeMap<>(Comparator.comparing(Currency::getCurrencyCode));
        jdbc.sql("""
                SELECT currency, a_net_minor, member_a FROM pair_net
                 WHERE member_a = LEAST(:a, :b) AND member_b = GREATEST(:a, :b)""")
                .param("a", a).param("b", b)
                .query(rs -> {
                    long net = rs.getLong("a_net_minor");
                    boolean aIsLower = rs.getObject("member_a", UUID.class).equals(a);
                    balances.put(Currency.getInstance(rs.getString("currency").strip()), aIsLower ? net : -net);
                });
        return balances;
    }

    /**
     * Reads a row and holds a row lock on it until the surrounding database transaction
     * ends. Must be called inside a transaction; with autocommit the lock is released
     * as soon as the statement returns and protects nothing.
     *
     * Lock order: the referenced row (loan, or correction target) before the item
     * being decided. See the class comment. Approving a correction locks one more row
     * before both: the loan its chain concerns ({@link #chainLoanOf}), which is the
     * oldest row of all, so the order still holds (Technical Design 4, CR-15).
     */
    public Optional<LedgerTransaction> lockForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transaction WHERE transaction_id = :id FOR UPDATE")
                .param("id", id)
                .query(TransactionRepository::mapRow)
                .optional();
    }

    /**
     * Moves a pending row to a decided status. The {@code status = 'pending'} guard
     * makes a stale or repeated decision update nothing (Ledger.Approval-8, -9), so the
     * return value is 1 when it applied and 0 when the row had already been decided.
     *
     * The history row and outbox row that accompany every decision are written by the
     * approval service (MB-12) in the same transaction.
     */
    public int recordDecision(UUID id, TransactionStatus to, Instant at, String declineReason) {
        if (to == TransactionStatus.PENDING) {
            throw new IllegalArgumentException("A decision moves a row out of pending");
        }
        return jdbc.sql("""
                UPDATE transaction
                   SET status = :to, decided_at = :at, decline_reason = :reason
                 WHERE transaction_id = :id AND status = 'pending'
                """)
                .param("to", to.dbValue())
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("reason", declineReason)
                .param("id", id)
                .update();
    }

    /**
     * Where an approved loan stands: Outstanding, Settled or Reversed (Technical Design 5,
     * SRS glossary). Computed from the ledger on every call, never stored.
     *
     * <h3>Reversal is a chain, so this counts it</h3>
     * A row is reversed by an approved compensating entry, which can itself be reversed
     * (L5 in the loan lifecycle), and so on. INT-6 allows at most one approved correction
     * per row, so the approved corrections of any row form a single chain. A row is
     * <em>in effect</em> when that chain has even length: zero (never corrected), two
     * (corrected, then the correction corrected), and so on.
     *
     * The recursive query below starts from the loan and from every approved repayment
     * of it, and walks each chain of approved corrections, recording how deep it goes.
     * Then:
     * <ul>
     *   <li>the loan is Reversed if its own chain is odd;</li>
     *   <li>otherwise Settled if any approved repayment's chain is even (the repayment
     *       stands);</li>
     *   <li>otherwise Outstanding. That includes a loan whose only repayment was
     *       reversed, which can be repaid again (T-Repay-10a, CR-14 Q1).</li>
     * </ul>
     * Pending repayments do not count here. Hiding a loan with a pending repayment from
     * the repayment form (Ledger.Repayment-8) is a separate query in MB-13.
     */
    public LoanLifecycle loanLifecycle(UUID loanId) {
        Optional<LedgerTransaction> loan = findById(loanId);
        if (loan.isEmpty()
                || loan.get().kind() != TransactionKind.LOAN
                || loan.get().status() != TransactionStatus.APPROVED) {
            return LoanLifecycle.NOT_AN_APPROVED_LOAN;
        }

        LoanStanding standing = standing(loanId, null);
        if (standing.reversed()) {
            return LoanLifecycle.REVERSED;
        }
        return standing.unreversedRepayments() > 0 ? LoanLifecycle.SETTLED : LoanLifecycle.OUTSTANDING;
    }

    // ---- MB-12: the approval queue ------------------------------------------------------

    /**
     * Items waiting for {@code memberId}'s decision: pending rows they are a party to
     * but did not initiate, so they are the counterparty (Ledger.Approval-1, -3; UI-8).
     * Loans, repayments and compensating entries alike, oldest first.
     *
     * Filtered by the member here, not by the caller (SEC-2, Technical Design 3).
     */
    public List<LedgerTransaction> awaitingDecisionBy(UUID memberId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transaction"
                        + " WHERE status = 'pending'"
                        + "   AND (creditor_id = :me OR debtor_id = :me)"
                        + "   AND initiated_by <> :me"
                        + " ORDER BY created_at, transaction_id")
                .param("me", memberId)
                .query(TransactionRepository::mapRow).list();
    }

    /**
     * Items {@code memberId} initiated that are still pending: what they are waiting on
     * others for, and what they may cancel (Ledger.Approval-11, UC-12). Oldest first.
     */
    public List<LedgerTransaction> awaitingOthersFor(UUID memberId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transaction"
                        + " WHERE status = 'pending' AND initiated_by = :me"
                        + " ORDER BY created_at, transaction_id")
                .param("me", memberId)
                .query(TransactionRepository::mapRow).list();
    }

    // ---- MB-12: approving a correction (SRS 1.12, CR-15) ---------------------------------

    /**
     * Whether {@code targetId} already has an approved compensating entry (INT-6). Read
     * after locking the target, so a correction approved concurrently is seen.
     *
     * Check it before approving a correction: the partial index
     * {@code transaction_one_approved_correction} would also refuse the second one, but a
     * failed statement aborts the whole database transaction in Postgres, so it is the
     * safety net, not the check.
     */
    public boolean hasApprovedCorrection(UUID targetId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM transaction
                     WHERE corrects_transaction_id = :target AND status = 'approved')""")
                .param("target", targetId)
                .query(Boolean.class)
                .single();
    }

    /**
     * The loan a compensating entry's correction chain concerns (SRS glossary,
     * Technical Design 5). Follows {@code corrects_transaction_id} back to the first row
     * of the chain; that row is either the loan itself, or a repayment, whose loan is
     * the one it settles.
     *
     * Empty when {@code correctionId} does not exist or is not a compensating entry. The
     * references are fixed when a row is inserted, so the answer never changes and can
     * be read before taking any lock: the approval service locks the loan it returns
     * first (Technical Design 4).
     */
    public Optional<UUID> chainLoanOf(UUID correctionId) {
        return jdbc.sql("""
                WITH RECURSIVE up (transaction_id, kind, settles_transaction_id,
                                   corrects_transaction_id, steps) AS (
                    SELECT transaction_id, kind, settles_transaction_id, corrects_transaction_id, 0
                      FROM transaction
                     WHERE transaction_id = :correction AND kind = 'compensating'
                  UNION ALL
                    SELECT t.transaction_id, t.kind, t.settles_transaction_id, t.corrects_transaction_id, up.steps + 1
                      FROM up
                      JOIN transaction t ON t.transaction_id = up.corrects_transaction_id
                )
                SELECT CASE kind WHEN 'loan' THEN transaction_id
                                 WHEN 'repayment' THEN settles_transaction_id END AS loan_id
                  FROM up
                 WHERE corrects_transaction_id IS NULL
                """)
                .param("correction", correctionId)
                .query((rs, n) -> rs.getObject("loan_id", UUID.class))
                .optional();
    }

    /**
     * Where {@code loanId} would stand if the pending compensating entry
     * {@code correctionId} were approved, without approving it: the check behind
     * Ledger.History-11 and INT-5 (SRS 1.12, CR-15). The entry is simply counted as
     * approved in the chain walk.
     *
     * To be read while holding the lock on the loan (Technical Design 4); otherwise a
     * repayment approved a moment later would not be seen. When raising a correction
     * (MB-14) it is read without the lock, as a courtesy check that approval repeats.
     */
    public LoanStanding loanStandingIfApproved(UUID loanId, UUID correctionId) {
        return standing(loanId, correctionId);
    }

    /**
     * Walks every correction chain hanging off a loan: the loan's own, and one from each
     * approved repayment of it. A row is in effect when its chain of approved corrections
     * has even length (INT-6 allows at most one approved correction per row, so each
     * chain is a single line). See {@link #loanLifecycle}.
     *
     * @param assumeApproved a pending compensating entry to count as approved, or null
     */
    private LoanStanding standing(UUID loanId, UUID assumeApproved) {
        record ChainDepth(boolean isLoan, int depth) { }

        List<ChainDepth> chains = jdbc.sql("""
                WITH RECURSIVE chain (root_id, node_id, depth) AS (
                    SELECT t.transaction_id, t.transaction_id, 0
                      FROM transaction t
                     WHERE t.transaction_id = :loan
                        OR (t.settles_transaction_id = :loan
                            AND t.kind = 'repayment'
                            AND t.status = 'approved')
                  UNION ALL
                    SELECT c.root_id, t.transaction_id, c.depth + 1
                      FROM chain c
                      JOIN transaction t
                        ON t.corrects_transaction_id = c.node_id
                       AND (t.status = 'approved' OR t.transaction_id = :assume)
                )
                SELECT root_id, MAX(depth) AS depth
                  FROM chain
                 GROUP BY root_id
                """)
                .param("loan", loanId)
                .param("assume", assumeApproved, java.sql.Types.OTHER)
                .query((rs, n) -> new ChainDepth(
                        rs.getObject("root_id", UUID.class).equals(loanId), rs.getInt("depth")))
                .list();

        Map<Boolean, List<ChainDepth>> byRole =
                chains.stream().collect(Collectors.partitioningBy(ChainDepth::isLoan));

        boolean reversed = byRole.get(true).stream().anyMatch(c -> c.depth() % 2 == 1);
        int standing = (int) byRole.get(false).stream().filter(c -> c.depth() % 2 == 0).count();
        return new LoanStanding(reversed, standing);
    }

    private static LedgerTransaction mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerTransaction(
                rs.getObject("transaction_id", UUID.class),
                TransactionKind.fromDb(rs.getString("kind")),
                rs.getObject("creditor_id", UUID.class),
                rs.getObject("debtor_id", UUID.class),
                rs.getLong("amount_minor"),
                Currency.getInstance(rs.getString("currency")),
                rs.getObject("transaction_date", java.time.LocalDate.class),
                rs.getString("note"),
                TransactionStatus.fromDb(rs.getString("status")),
                rs.getString("decline_reason"),
                rs.getObject("initiated_by", UUID.class),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("decided_at", OffsetDateTime.class)),
                rs.getObject("settles_transaction_id", UUID.class),
                rs.getObject("corrects_transaction_id", UUID.class),
                rs.getBoolean("is_opening_balance"),
                rs.getObject("submission_key", UUID.class));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
