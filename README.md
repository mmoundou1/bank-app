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
| [Test Derivation and Validation](https://moundou.atlassian.net/wiki/spaces/SD/pages/2883588) | 104 test cases derived before any code. |
| [Vision and Scope](https://moundou.atlassian.net/wiki/spaces/SD/pages/1572865) | Business problem, objectives, risks. |

Backlog: Jira project **MB**.

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

## Deployment

Built from the `Dockerfile` and deployed to Render on push to `main`.
Render has no native Java runtime, which is why this is a container.

The JVM flags in the Dockerfile are load-bearing: the free tier caps at 512 MB,
and `MaxRAMPercentage` reads the container limit rather than the host's memory.

## Conventions

- Money is **integer minor units**. No float or decimal in the money path (CON-5).
- `transaction_date` is a **calendar date**, never a timestamp (ADR-005).
- All dates are evaluated in `America/New_York`, never the system default (CON-8).
- Approved ledger rows are **never updated or deleted**. Corrections are new
  compensating entries (ADR-003).
