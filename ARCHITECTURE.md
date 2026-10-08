# Code map

This page tells you where things are in the code and why they are there. It does
not repeat the requirements or the design. Those live in Confluence, and this
page points to them.

- **SRS** says *what* the app must do. Every rule has an ID such as `Ledger.Entry-8`.
- **Technical Design (TD)** says *how* it is built: modules, locks, routes.
- **ADR log** says *why* the expensive choices were made.
- **This page** says *where* each of those lives in the code.

Keep it current. A pull request that adds a package, a screen or a story updates
this page in the same pull request.

---

## 1. Three ways into the code

You never need to read the code base front to back. Pick the way in that matches
your question.

**"Where is requirement X?"** Search the whole project for its ID. In IntelliJ,
use *Find in Files* (Cmd+Shift+F on Mac, Ctrl+Shift+F on Windows). Every rule
is tagged where it is enforced and where it is tested. For example, searching
`Ledger.Entry-8` finds the check in `EntryValidator`, its message key in
`messages.properties`, and its tests in `EntryValidatorTests`. This search is the
link between the documents and the code.

**"What happens when someone uses page Y?"** Start from the URL. Search for the
route, such as `"/admin/allow-list"`, to land in a controller. Then follow the
calls downward with *Go to Declaration* (Cmd+B on Mac, Ctrl+B on Windows).
Every request passes through the same layers, described in section 2.

**"What does class Z promise?"** Read its test before the class itself. Test names
are sentences, such as `aServiceRefusalIsA403EvenIfTheSessionClaimsTheRole`, so
the test file reads like a specification.

---

## 2. How a request travels

Every page works the same way. Here is the journey of an administrator adding
an email address:

```
Browser  POST /admin/allow-list  email=ama@example.com
   │
   ▼
SecurityConfig ........ Is anyone signed in? Is it an administrator?   (outer wall)
   │                    No  → sign-in page, or 403. No controller runs.
   ▼
AdminController ....... web layer: reads the form, calls ONE service method,
   │                    chooses the page or redirect. No rules, no SQL.
   ▼
AdminService .......... service layer: the rules. Re-checks from the database that
   │                    the actor is an active administrator, normalizes the
   │                    address, decides. Owns the database transaction.
   ▼
MemberAdministration .. data layer: the SQL, written out in full. No rules.
   │
   ▼
Postgres (tables created by db/migration/V1__ledger_schema.sql)
```

On the way back, the controller redirects to `GET /admin`. That page is rendered
from the template `templates/admin.html`, and every word on it comes from
`messages.properties`.

Three rules hold everywhere (TD §2 and §6). Knowing them tells you where to look:

1. **Controllers call services, services call repositories.** A controller never
   runs SQL, and a repository never decides anything.
2. **Authorization is enforced in the service**, re-read from the database.
   `SecurityConfig` is only a second layer. So if you want to know who may do
   something, read the service.
3. **Rules that need no database are pure classes** (`EntryValidator`,
   `ProfileRules`, `Amounts`), so the fast suite can test them in milliseconds.
   The service calls them, then does the database work.

---

## 3. Packages

All code is under `src/main/java/com/moundou/bank/`.

| Package | Job | Start with |
|---|---|---|
| `identity` | Who someone is and what they may do: Google sign-in, members, roles, the allow-list, administration, profiles | `SignInService`, `AdminService` |
| `ledger` | The money records: entering transactions, and later approving, repaying, correcting and history | `EntryService`, `TransactionRepository` |
| `notification` | Queuing email alerts in the same database transaction as the ledger change (the "outbox") | `OutboxWriter` |
| `web` | Controllers (one per screen) and `SecurityConfig`. Templates live in `resources/templates` | the controller for the screen |
| `i18n` | Turning amounts and dates into text | `Formats` |
| `health` | The `/healthz` keep-alive endpoint | `HealthController` |
| `ops` | Running database migrations at startup | `StartupMigration` |
| *(root)* | `BankApplication` (starts the app) and `NotPermittedException` (used by every module) | |

### `identity`

| Class | What it is |
|---|---|
| `GoogleSignIn` | Where Spring Security hands a Google sign-in to our code. Turns the answer into a session or a refusal. |
| `SignInService` | The four sign-in checks: verified email, allow-list, find or create the member, still active. *(You wrote this one.)* |
| `SignedInMember` | Who is signed in, as stored in the session. Controllers get it with `@AuthenticationPrincipal`; `memberId()` is the "actor" every service needs. |
| `GoogleIdentity`, `SignInResult`, `NewMemberAccount` | Plain data passed in and out of `SignInService`. |
| `AllowList` / `JdbcAllowList`, `MemberAccounts` / `JdbcMemberAccounts` | Data access for sign-in. Each is an interface plus a database version, so `SignInServiceTests` can swap in in-memory versions. |
| `MemberDirectory` | How *other* modules look up a member (name, time zone, active), and who can be chosen as counterparty (`counterpartiesFor`). The ledger and the entry screen ask here and never read the `member` table themselves. |
| `Member`, `MemberAccount`, `Role` | Member records. `Member` is the ledger's view; `MemberAccount` adds the role. |
| `AdminService` | Administrator operations: the allow-list, deactivation, and the "last administrator" rule. |
| `MemberAdministration` | SQL for administration and profiles, including the row lock behind the "last administrator" rule. |
| `ProfileService`, `ProfileRules` | A member changes their own name and time zone. `ProfileRules` holds the rules as pure code. |
| `EmailAddresses` | Normalizes a typed address (trims it and lower-cases it). |
| `AdminRuleException` | "You're an administrator, but a rule forbids this", for example removing the last administrator. |
| `GoogleCredentialsCheck` | A warning at startup when the Google credentials are missing. |
| `IdentityConfig`, `IdentityProperties` | Wiring, and the `bank.identity.*` settings from `application.yml`. |

### `ledger`

| Class | What it is |
|---|---|
| `EntryService` | Records a new loan, split or opening balance: one transaction row, one history row and one outbox row, all or nothing. Handles retries using the submission key. |
| `EntryValidator` | Every entry rule (`Ledger.Entry-1` to `-16`) as pure code. Returns all the field errors at once. |
| `EntryRequest` | What the entry form submits, as typed. The form's input for `EntryService`. |
| `Amounts` | Parses typed text such as "12.50" into integer minor units (1250), with no floating point anywhere. |
| `NewTransaction`, `LedgerTransaction` | A row about to be written, and a row read back. |
| `TransactionKind`, `TransactionStatus`, `LoanLifecycle` | Enums that mirror the database's allowed values. `dbValue()` gives the value as stored in the database. |
| `TransactionRepository` | All SQL on `transaction`: insert, lock, find, pair balances, and whether a loan is outstanding. Its Javadoc explains the repayment lock (INT-5). For MB-12 it also reads the two approval queues, finds the loan a correction's chain concerns (`chainLoanOf`), and says where that loan would stand if the correction were approved (`loanStandingIfApproved`, CR-15). |
| `LoanStanding` | What `loanStandingIfApproved` returns: is the loan reversed, and how many of its repayments still stand. The service decides from it. |
| `AuditService` | The administrator's view of two members' dealings. It lives here because it reads ledger data; TD §2 lists it under `identity`. |
| `LedgerConfig`, `LedgerProperties` | Wiring: the clock and the onboarding end date. |

### `web`

| Class | Routes | Template |
|---|---|---|
| `SignInController` | `GET /login`, `GET /` | `login.html`, `home.html` |
| `ProfileController` | `GET`/`POST /profile` | `profile.html` |
| `EntryController` | `GET /transactions/new`, `POST /transactions` | `transactions-new.html` |
| `AdminController` | `/admin`, `/admin/allow-list`, `/admin/allow-list/remove`, `/admin/members/{id}/deactivate`, `/admin/audit` | `admin.html`, `audit.html` |
| `SecurityConfig` | Decides which routes are open, which need sign-in, and which need the administrator role | |

| `DualConfirmationController` *(MB-12, being built)* | `GET /pending/{id}`, `POST /pending/{id}/approve`, `POST /pending/{id}/decline`, `POST /transactions/{id}/cancel` (TD §8) | `pending.html`; the queue is `queue.html`, shown on `home.html` |

`layout.html` holds the shared `<head>`, styles and navigation, and a `flash`
fragment that shows `messageKey`/`messageArgs` or `errorKey` after a redirect. Every page pulls
them in with `th:replace="~{layout :: head('title.key')}"` and `~{layout :: nav}`.

### Outside `java/`

| Path | What it is |
|---|---|
| `resources/db/migration/` | The database schema. `V1` holds the ledger tables and balance views; `V2` holds the session tables. **Never edit a merged file.** A change is always a new file, `V3__...sql`. |
| `resources/messages.properties` | Every word a member sees, grouped by screen. Templates use `#{key}`. |
| `resources/templates/` | One Thymeleaf page per screen, plus `layout.html`. |
| `resources/application.yml` | Settings. Secrets come from environment variables, never from this file. |

---

## 4. Patterns you will see repeatedly

Once you recognize these, most classes read quickly.

- **Outcome or Result types.** A service returns a small sealed interface
  rather than throwing on bad input, for example
  `EntryService.Outcome = Recorded | Rejected(fieldErrors)`. The controller uses
  a `switch` to pick what to show. `fieldErrors` maps a form field to a message
  key, so the page can print each error beside its field.
- **Exceptions are for "you may not", not for "you typed it wrong."**
  `NotPermittedException` becomes a 403 automatically. `AdminRuleException`
  carries a message key to show.
- **The actor comes first and comes from the session.** Service methods look like
  `update(UUID actorId, ...)`. The controller passes `me.memberId()` from
  `SignedInMember`, never an id taken from the form.
- **Message keys, never text.** Java returns keys such as
  `"ledger.entry.date.future"`, and the template turns them into words. If you
  write a literal word in a template, `TemplateTextTests` fails the build.
- **Money is a `long` of minor units plus a `Currency`.** 1250 USD means $12.50;
  1250 XAF means 1,250 francs. It only becomes text in `Formats`.
- **Enums mirror database values.** `TransactionStatus.PENDING.dbValue()` is
  `"pending"`, which matches the `CHECK` constraint in `V1`.

---

## 5. Tests

Tests sit in `src/test/java` under the same package as the class they test.

| Name ends in | Suite | Needs | Run with |
|---|---|---|---|
| `...Tests` | fast | nothing | `mvn test` |
| `...IT` | slow | Postgres | `mvn verify -DskipUnitTests=true`, with Docker or with `IT_DATABASE_URL` (see README) |

- `web/*Tests` start only the web layer (`@WebMvcTest`), with the services
  replaced by stand-ins (`@MockBean`). They test routes, security and rendering.
- `*IT` tests run the real SQL. `support/TestDatabase` points them at a database
  it wipes; `support/LedgerFixtures` creates members and transactions for them;
  `support/MutableClock` lets a test change "today".
- Test IDs from the Test Derivation page, such as `T-Entry-8a`, appear in test
  names or comments, so searching for one finds its test.

---

## 6. Stories and where their code is

| Story | Status | Code |
|---|---|---|
| MB-7 Schema and migrations | Done | `db/migration/V1`, `TransactionRepository`, `ops/StartupMigration` |
| MB-9 Sign-in | In progress (awaiting a check that sessions survive redeploys) | `identity/GoogleSignIn`, `SignInService`, `web/SecurityConfig`, `SignInController` |
| MB-10 Roles and profile | In progress (awaiting a check on the live site) | `identity/AdminService`, `ProfileService`, `MemberAdministration`, `ledger/AuditService`, `web/AdminController`, `ProfileController` |
| MB-11 Transaction entry | In progress (screen built; follow-ups in section 7) | `ledger/EntryService`, `EntryValidator`, `Amounts`, `EntryRequest`; `web/EntryController`, `templates/transactions-new.html`; `identity/MemberDirectory.counterpartiesFor` |
| MB-12 Approval | In progress | Templates `pending.html`, `queue.html`; queries in `TransactionRepository` (queues, `chainLoanOf`, `loanStandingIfApproved`, `hasApprovedCorrection`), tested in `ApprovalQueriesIT`. Still to write: `TransactionStateMachine` (TD §5), the approval service, and `DualConfirmationController`. |
| MB-13 Repayment | To do | `ledger`. `TransactionRepository.loanLifecycle` is ready for it. |
| MB-14 History and corrections | To do | `ledger` |
| MB-15, MB-17 Dashboard and counterparties | To do | `ledger` queries over the `member_net` and `pair_net` views (already in `V1`); `home.html` |
| MB-18, MB-19 Alerts | To do | `notification`: the worker that sends the outbox rows `EntryService` already writes |
| MB-6 Hosting | Partly done | Render, Neon and Google are live. Still to do: email provider, keep-alive scheduler, switching to Neon's direct endpoint |

---

## 7. MB-11: how the entry screen works, and what is left

The screen follows section 2 exactly. `EntryController` reads the form into an
`EntryRequest` (every input's `name` in the template matches a field of the record)
and passes it to `EntryService.submit` with the actor from the session.

| Outcome | What the member sees |
|---|---|
| `Recorded` | A redirect to a **fresh** form with a confirmation above it: "Sent to Ben for approval: you lent them $20.00 on Sep 30, 2026." The direction comes from the saved transaction, never from the form. |
| `Recorded` with `alreadyRecorded` | The same, but "This was already sent: …", naming the entry actually on record (Ledger.Entry-14). |
| `Rejected` | The form again, with each error beside its field, what was typed kept, and the same submission key (nothing was saved). |

Decisions worth knowing (recorded on MB-11 in Jira, 2026-10-03):

- **Resending an old form** (pressing Back after a save) counts as a retry, because
  the page still holds the old submission key. The confirmation therefore names
  what was recorded, and the form page is never cached by the browser, so Back
  usually fetches a fresh key. Detecting a changed resend is deferred.
- **Checkboxes.** An unticked box sends nothing, so the template sends a hidden
  `_split` marker that Spring reads as `false`. Without it, every unsplit entry
  would fail to bind.
- **Confirmation details travel as plain strings** in flash attributes, because
  flash attributes are stored in the session, and sessions live in the database
  and must be serializable.

`EntryWebTests` covers all of the above; each test was checked by breaking the
controller or template on purpose.

**Still to do for MB-11:**

| Piece | Where |
|---|---|
| Opening balances during onboarding (Ledger.Entry-15, -16): a checkbox shown only while the window is open. The template currently sends `openingBalance=false` | `EntryController` (is the window open?), `transactions-new.html` |
| `GET /transactions/new/split-preview`: "Ben will owe you $12.51" while typing a split (UI-7) | `EntryController`; uses `EntryValidator.splitObligation`; htmx, new for this project |
| The initiator's pending list (acceptance criterion: the item appears there) | Home page, with the dashboard (MB-15) |
| USE-1: an entry in under 30 seconds on a real phone | Manual check after deploy |

---

## 8. Where the code differs from the Technical Design

Differences that are known and deliberate:

- The health endpoint is in `health`, not in `ops` (TD §2).
- `AuditService` is in `ledger`, not in `identity` (TD §2), because it reads ledger data.
- The app connects through Neon's pooler endpoint, not the direct endpoint TD §10
  asks for. This is to be fixed under MB-6.
