# Architecture

```
Browser (React SPA, installable PWA)
   │  HTTPS, one origin: the app and /api
   ▼
nginx ── static app (strict CSP, long-cached hashed assets)
   │  /api → backend (assistant stream unbuffered)
   ▼
Spring Boot 4.1 on Java 25 ── one deployable, modules by feature
   │   ├─ REST API under /api/v1, Server-Sent Events for the assistant
   │   ├─ scheduled jobs: recurring postings, notifications, price feeds
   │   ├─ LLM port → Anthropic SDK | OpenAI-compatible HTTP | offline fake
   │   └─ price port → Nobitex | any JSON API
   ▼
PostgreSQL 16 (Flyway migrations; sessions stored here too)
```

A modular monolith: a personal finance app gains nothing from network hops between services,
and one process with one database keeps backups, upgrades and transactions simple. Boundaries
between modules are enforced with ArchUnit tests (no cycles; the core finance modules do not
depend on reports or AI; the LLM adapters depend on nothing but their port).

## Principles

1. **Money is never a float.** `BigDecimal` in Java, `NUMERIC` in the database, strings in JSON
   (`"2500000"`), formatted in the browser without passing through a JavaScript number.
2. **Toman is the base unit.** Rial appears only at the edges (bank SMS, price APIs, statement
   imports) and is converted there, in code, so a tenfold error cannot slip in.
3. **Dates are stored Gregorian and shown Jalali.** A "month" is a Jalali month everywhere:
   budgets, reports, recurring rules, goals. Days are counted in `KARNAME_TIMEZONE` (Tehran).
4. **Balances are derived, never edited.** An account's balance is its opening entry plus its
   transactions; corrections are reconciliation entries. Values in other units use the price in
   effect on the day.
5. **Every query is scoped to its user**, and every referenced id (an account in a transaction,
   a category, a goal's accounts) is checked against that user. Tests cover isolation per
   resource.
6. **The app is complete without AI.** AI is a layer on top, behind a port, and the model never
   computes figures or writes data on its own.

## Backend modules (`backend/src/main/java/ir/karname`)

| Module | Responsibility |
|---|---|
| `common` | Jalali calendar, Persian text and digits, number bounds, errors as RFC 9457 problem details with Persian messages, AES-GCM encryption of secrets, outbound address checks |
| `user` | Registration (the first user is the administrator), sign-in with sessions, two-factor sign-in, settings, administration of users, deletion of an account with all its data |
| `commodity` | Units (Toman, currencies, gold, coins, crypto, custom ones) and their price history |
| `account` | Accounts of every type, with bank and card-number hints for SMS matching |
| `category` | Two-level income and expense categories with Persian defaults, and merchant rules learned from descriptions |
| `transaction` | Income, expense, transfer (with two amounts across units), fees, tags, search, duplicate checks |
| `ledger` | Balances, valuation on a date, net worth in several units, cost basis, the dashboard |
| `budget`, `report`, `goal` | Monthly budgets, reports and unusual spending, goals with projections |
| `loan`, `recurring`, `cheque`, `forecast`, `notification` | Obligations, schedules, the cash-flow forecast and in-app notifications |
| `pricefeed` | Automatic price sources and their scheduler |
| `io` | CSV export, statement import, JSON backup and restore |
| `ai` | The LLM port and adapters, services and routing, finance tools, the chat loop, capture (quick add, SMS), insight (reports, categorization), usage and quotas |
| `demo` | Optional sample data for a demo user |

## The AI layer

- **Port.** `LlmClient` takes a provider-neutral request (system prompt, messages made of typed
  parts, tool specifications, an optional output schema, effort, token limit) and streams text
  back through a listener. Three adapters implement it: the official Anthropic SDK, a hand-written
  OpenAI-compatible client (Chat Completions with streaming, tool calls and JSON Schema), and a
  deterministic fake for tests and demos.
- **Tools.** Fifteen in all: twelve read the user's data (overview, accounts, categories,
  transactions, totals by category and by month, budgets, net-worth history, allocation, goals,
  obligations, prices); the others are an exact calculator, a savings projection, and
  `propose_transactions`, which only returns drafts. Inputs are validated against their schema before running; tool results are
  JSON with Jalali dates and Toman amounts.
- **Chat.** A loop of model calls and tool runs (at most ten rounds, four minutes), streamed to the
  browser as events (`start`, `tool`, `text`, `drafts`, `done`, `error`). Conversations are
  append-only and stored with each provider's native message format, so a provider gets back
  exactly what it produced (Claude's thinking blocks included); a conversation stays on the
  provider and model it started with. A failed turn is kept but excluded from what is sent next.
- **Structured tasks** (quick add, SMS, report, categorization) ask for JSON matching a schema:
  natively where supported, otherwise described in the prompt and read leniently, with one retry
  that lists the problems.
- **Models without tools** get a deterministic snapshot of the user's figures attached to the
  question instead.
- **Claude specifics:** adaptive thinking with explicit effort per task, strict tool schemas,
  prompt caching of the system prompt and tools (the date and other changing context come after
  the cache breakpoint), refusal handling with an optional server-side fallback model, and
  capability detection from the Models API. A request feature a gateway rejects is remembered and
  dropped.

## Security

- Server-side sessions (Spring Session JDBC) in HttpOnly, SameSite=Lax cookies; CSRF tokens for
  every state-changing request; the session id changes at sign-in; changing a password or role
  ends the user's other sessions.
- Passwords hashed with bcrypt; failed sign-ins limited per username and address and per
  username overall; registration limited per address; optional TOTP with single-use codes and
  recovery codes, enabled only after re-entering the password.
- The client address comes from forwarding headers only when the request arrives from a private
  network address (the nginx container), and nginx overwrites those headers with the real
  address.
- API keys and secret headers encrypted at rest, never returned, and only sent to the address
  they were saved for; outbound calls do not follow redirects and may not target link-local
  addresses.
- Numbers too large or too fine to compute with are refused when a request is read.
- nginx: a Content-Security-Policy allowing scripts only from the same origin, frame denial,
  no-sniff, a strict referrer policy and a request limit on password endpoints.

## Frontend (`frontend/src`)

React 19 with TypeScript, Vite, Tailwind CSS (logical properties only, for right-to-left), Radix
primitives with in-repo components, TanStack Query, React Router, react-hook-form with Zod,
Recharts, and a Jalali date picker written for the project. Amounts are typed with live
separators, Persian/Arabic/Latin digits, quick "thousand"/"million" buttons and the amount spelled
out in words. Assistant answers are rendered as Markdown without raw HTML.

`features/*` holds one folder per area (pages, dialogs, API hooks); `lib/` holds formatting,
Jalali and Persian helpers; `components/` the shared UI.

## Tests

- Backend: unit tests for the calendar, money, parsing, schedules and AI pieces; integration
  tests against PostgreSQL for every API, user isolation, the agent loop with the fake model and
  both LLM adapters against local stub servers; architecture rules.
- Frontend: Vitest and Testing Library for formatting, inputs and pages.
- End to end: Playwright against the real backend (offline model, its own database) and the
  production build, on a desktop and a phone viewport.
