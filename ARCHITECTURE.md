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
| `MemberDirectory` | How *other* modules look up a member (name, time zone, active). The ledger asks here and never reads the `member` table itself. |
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
| `TransactionRepository` | All SQL on `transaction`: insert, lock, find, pair balances, and whether a loan is outstanding. Its Javadoc explains the repayment lock (INT-5). |
| `AuditService` | The administrator's view of two members' dealings. It lives here because it reads ledger data; TD §2 lists it under `identity`. |
| `LedgerConfig`, `LedgerProperties` | Wiring: the clock and the onboarding end date. |

### `web`

| Class | Routes | Template |
|---|---|---|
| `SignInController` | `GET /login`, `GET /` | `login.html`, `home.html` |
| `ProfileController` | `GET`/`POST /profile` | `profile.html` |
| `AdminController` | `/admin`, `/admin/allow-list`, `/admin/allow-list/remove`, `/admin/members/{id}/deactivate`, `/admin/audit` | `admin.html`, `audit.html` |
| `SecurityConfig` | Decides which routes are open, which need sign-in, and which need the administrator role | |

`layout.html` holds the shared `<head>`, styles and navigation. Every page pulls
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
| MB-11 Transaction entry | **Service done, screen not started** | Done: `ledger/EntryService`, `EntryValidator`, `Amounts`, `EntryRequest`. See section 7 for the rest. |
| MB-12 Approval | To do | Will add `TransactionStateMachine` (TD §5) and an approval service to `ledger`. `TransactionRepository.lockForUpdate` and `recordDecision` are ready for it. |
| MB-13 Repayment | To do | `ledger`. `TransactionRepository.loanLifecycle` is ready for it. |
| MB-14 History and corrections | To do | `ledger` |
| MB-15, MB-17 Dashboard and counterparties | To do | `ledger` queries over the `member_net` and `pair_net` views (already in `V1`); `home.html` |
| MB-18, MB-19 Alerts | To do | `notification`: the worker that sends the outbox rows `EntryService` already writes |
| MB-6 Hosting | Partly done | Render, Neon and Google are live. Still to do: email provider, keep-alive scheduler, switching to Neon's direct endpoint |

---

## 7. Where new code goes: the rest of MB-11

The service half of MB-11 is finished and tested. The screen half follows the
same path as section 2, using the routes from TD §8:

| Piece | Where | Model it on |
|---|---|---|
| `GET /transactions/new`: show the form, with a fresh `submissionKey` in a hidden field and the member list for the counterparty dropdown | new `web/EntryController` | `ProfileController.page` |
| `POST /transactions`: build an `EntryRequest` from the form and call `EntryService.submit(me.memberId(), request, locale)`. `Recorded` → redirect with a confirmation; `Rejected` → show the form again with the errors and the typed values | same controller | `ProfileController.save` |
| The form | new `templates/transaction-new.html` | `profile.html` (fields, inline errors) |
| Every label and hint | `messages.properties`, under a new `entry.*` group | the `profile.*` group |
| `GET /transactions/new/split-preview`: shows "Ben will owe you $12.51" while typing a split | same controller; uses `EntryValidator.splitObligation` | *(htmx; new for this project)* |
| Web tests: members only, CSRF required, errors shown beside fields, a retry recorded once | new `web/EntryWebTests` | `RolesAndProfileWebTests` |

The counterparty dropdown needs a list of active members other than you. No
method returns that yet. It belongs in `MemberDirectory` (`identity`), because the
web layer may not query `member` itself.

---

## 8. Where the code differs from the Technical Design

Differences that are known and deliberate:

- The health endpoint is in `health`, not in `ops` (TD §2).
- `AuditService` is in `ledger`, not in `identity` (TD §2), because it reads ledger data.
- The app connects through Neon's pooler endpoint, not the direct endpoint TD §10
  asks for. This is to be fixed under MB-6.
