# Moundou's Bank

A private financial ledger for the Moundou family. It records money already
exchanged between members; it holds no funds, moves no money, and connects to
no payment network.

**Release 1.0** - Java 21, Spring Boot 3, server-rendered with Thymeleaf and htmx,
Postgres, deployed as a container.

## Requirements and decisions

The specification is baselined. Changes go through the process in SRS section 1.5
rather than being edited in directly.

| Document | Purpose |
|---|---|
| [Software Requirements Specification](https://moundou.atlassian.net/wiki/spaces/SD/pages/2457607) | Baselined requirements. Start here. |
| [Architecture Decision Log](https://moundou.atlassian.net/wiki/spaces/SD/pages/2818069) | Why the stack looks like this, and what each choice cost. |
| [Requirements Traceability Matrix](https://moundou.atlassian.net/wiki/spaces/SD/pages/2228228) | Requirement to feature to Jira, both directions. |
| [Test Derivation and Validation](https://moundou.atlassian.net/wiki/spaces/SD/pages/2883588) | 133 test cases derived before any code. |
| [Technical Design](https://moundou.atlassian.net/wiki/spaces/SD/pages/9502722) | Modules, transaction boundaries, state machine, screens and routes. |
| [Vision and Scope](https://moundou.atlassian.net/wiki/spaces/SD/pages/1572865) | Business problem, objectives, risks. |

Backlog: Jira project **MB**.

**New to the code?** Read [ARCHITECTURE.md](ARCHITECTURE.md) first: it maps packages,
screens and stories to the code, and shows how to go from a requirement ID to the
line that enforces it.

## Running locally

    mvn spring-boot:run          # starts on :8080, no database needed
    curl localhost:8080/healthz

Without `DATABASE_URL` the app still starts and reports the database as
`unconfigured`. That is deliberate - it keeps the first deploy green before
MB-6 provisions Postgres.

## Tests

    mvn test                             # fast suite, no containers
    mvn verify -DskipUnitTests=true      # slow suite, needs a container runtime

The split is not tidiness. Integration tests run real Postgres in a container
and prove things unit tests cannot - transaction atomicity (CON-3), and later
the fault-injection cases of ADR-009.

No container runtime? Point the slow suite at any Postgres 16 you can throw away.
The database it names is wiped on every run:

    IT_DATABASE_URL='jdbc:postgresql://localhost:5432/bank_it?user=bank' \
      mvn verify -DskipUnitTests=true

## Database

The schema lives in Flyway migrations in `src/main/resources/db/migration` and is
applied when the application starts (ADR-015). A migration is never edited once
merged; a change is always a new file. Data access is plain SQL through
`JdbcClient`, with no JPA.

If the database cannot be reached at startup, the application logs an error and
starts anyway, so it can serve its degraded mode (AVL-2); migrations then run at
the next startup that reaches the database. A migration that fails on a reachable
database still stops startup (`StartupMigration`).

## Deployment

Built from the `Dockerfile` and deployed to Render on push to `main`.
Render has no native Java runtime, which is why this is a container.

The JVM flags in the Dockerfile are load-bearing: the free tier caps at 512 MB,
and `MaxRAMPercentage` reads the container limit rather than the host's memory.

## Conventions

- Money is **integer minor units**. No float or decimal in the money path (CON-5).
- Money is stored in the **transaction's own currency** (USD, XAF, EUR) and never
  converted. XAF has no minor unit (ADR-013).
- `transaction_date` is a **calendar date**, never a timestamp (ADR-012).
- Dates are defaulted and validated in the **initiating member's time zone**, never
  the server's (CON-8, ADR-012). Timestamps are stored in UTC.
- Approved ledger rows are **never updated or deleted**. Corrections are new
  compensating entries (ADR-003).
- A repayment is approved only while **holding a lock on its loan**, loan first,
  then the repayment (INT-5, `TransactionRepository`). There is deliberately no
  unique constraint on `settles_transaction_id`.
- **No user-facing text in templates or code.** Everything a member reads comes
  from `messages.properties` via `#{key}` (ADR-014, I18N-1). The build fails on a
  literal string or a missing key (`TemplateTextTests`). Release 1.0 is English
  only; the browser's language is deliberately ignored.
- Layout **scales with text size**: `rem`/`em` units, no fixed heights on text,
  nothing that blocks zoom (USE-4, ADR-014).
