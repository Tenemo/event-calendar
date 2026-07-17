# Shared calendar

Shared calendar is a server-rendered web application for event calendars shared among friends. It is intended for kayaking plans, birthdays, trips, and similar coordination without notifications or a native mobile app.

## Stack

- Java 25 runtime and source target
- Maven Wrapper and WAR packaging
- Open Liberty with Jakarta EE 10 Web Profile
- Jakarta Faces / JSF and PrimeFaces with the `jakarta` classifier
- CDI, EJB Lite services, Jakarta Security, and provider-neutral JPA
- Flyway database migrations
- PostgreSQL 17
- Docker and Docker Compose

## Product model

- Registration is invitation-only.
- Every registered user can create multiple calendars.
- A calendar creator receives that calendar's `ADMIN` role.
- Calendar roles are scoped to one calendar: `EDITOR` and `ADMIN`.
- Calendar invitations grant `EDITOR`; read-only access uses the calendar's bearer link rather than a membership role.
- Signed-in users can change their own password from account settings; doing so invalidates every existing session.
- Calendars are public by default through compact, random bearer links.
- Calendar links with public access are read-only and marked `noindex, nofollow`.
- Events support titles, locations, descriptions, inclusive all-day date ranges, and timed date ranges. All-day dates are normalized in the calendar's IANA time zone instead of assuming every day is 24 hours.
- Recurrence, notifications, email delivery, ICS import/export, and native mobile apps are outside the current scope.

## Local development

Install `mise` and Docker, then run:

```bash
mise trust
mise run setup
mise run db
mise run dev
```

The application listens on `http://localhost:9080` by default. Its database-aware health endpoint is `http://localhost:9080/health`. It returns `200 ok` only when PostgreSQL is reachable and `503 unavailable` otherwise.

Jakarta Faces extensionless routing is enabled. Browser-facing routes include `/login`, `/register`, `/app/calendars`, `/app/account-settings`, `/app/calendar-members`, `/app/calendar-settings`, and `/app/invitations`. Every calendar uses one 11-character token directly at the root, such as `https://calendar.social/Abc_123-xY0`, as its canonical URL for editors, admins, and anonymous readers. The root 11-character Base64URL namespace is reserved for calendars. The `.xhtml` files are internal templates, not canonical browser URLs.

## Environment variables

Copy `.env.example` to `.env` for local development. Do not commit `.env`.

| Variable | Local default | Purpose |
| --- | --- | --- |
| `PORT` | `9080` | Liberty HTTP port, or the host port used by the Compose web service. Railway injects this value. |
| `HTTPS_PORT` | `9443` | Optional local Liberty HTTPS listener. Railway terminates HTTPS at its proxy. |
| `PGHOST` | `localhost` | PostgreSQL host. The Compose web container uses `postgres`. |
| `PGPORT` | `5432` | PostgreSQL port. |
| `PGDATABASE` | `calendar` | PostgreSQL database name. |
| `PGUSER` | `calendar` | PostgreSQL user. |
| `PGPASSWORD` | `calendar` | PostgreSQL password. Use a generated secret outside local development. |
| `APP_TIMEZONE` | `Europe/Warsaw` | Default IANA time zone assigned to new calendars. |
| `APP_BASE_URL` | `http://localhost:9080` | Canonical external base URL used for invitation and calendar links. |
| `APP_BOOTSTRAP_INVITE_TOKEN` | blank | Optional one-time admission secret for creating the first account on a database that has never contained an account. |

`APP_TIMEZONE` must be an identifier supported by Java's IANA time-zone database. Invalid values stop application startup.

`APP_BASE_URL` must be an absolute HTTP or HTTPS URL without credentials, query parameters, or a fragment. A malformed configured value stops application startup. Request-derived links are accepted only on loopback development hosts; production requires an explicit value.

`PGSSLMODE` is used only by the Dockerized PostgreSQL backup client. Set it to the mode required by the remote database, normally `require` for a public production endpoint.

## Database migrations

Flyway runs during application startup and owns the database schema. The application fails startup when a migration cannot be applied. The current schema is migration version 12. Migration 8 removes existing read-only memberships rather than promoting them to editor access and restricts calendar memberships to `EDITOR` and `ADMIN`. Migration 9 adds the password version used to invalidate older authenticated sessions after a password change. Migration 10 replaces every existing calendar bearer token with the compact format, so deploying it invalidates every previously shared calendar URL once. Migration 11 caps every existing invitation at seven days after creation and adds a database constraint that prevents longer lifetimes. Migration 12 audits existing calendar time zones without changing them and fails closed if any stored value is not an exact identifier supported by Java's IANA time-zone database. If that audit fails, inspect the distinct `calendar.timezone` values, back up the database, map each unsupported value to its intended supported region identifier, and restart the application so Flyway retries the migration. Host-installed PostgreSQL client programs are not required.

Start and inspect the local database with:

```bash
mise run db
docker compose exec postgres psql -U calendar -d calendar -c '\dt'
docker compose exec postgres psql -U calendar -d calendar -c 'select installed_rank, version, description, success from flyway_schema_history order by installed_rank;'
```

## Running tests

Run the unit tests and build the WAR:

```bash
mise run package
```

Browser tests build the production image and run against disposable application and PostgreSQL containers on non-default loopback ports. They do not use or modify the persistent development database. Chromium is installed automatically by default.

```bash
mise run e2e
```

Set `BROWSER` to `firefox` or `webkit` when intentionally running another supported browser. Pull requests use Chromium. The automated suite covers registration, login, logout, password changes and session revocation, calendar creation, event creation/editing/deletion, compact canonical calendar links, public-access disabling, link regeneration, invitation acceptance, editor removal, last-admin protection, validation, request throttling, and anonymous read-only behavior. A second isolated scenario verifies bootstrap-registration rollback and concurrency.

Run only the isolated bootstrap-registration verification with:

```bash
mise run verify-bootstrap-registration
```

Production packaging and recovery have separate checks:

```bash
mise run docker-build
mise run verify-backup-restore
```

Pull requests run the Maven build, PostgreSQL-backed Playwright workflows, a production image/container smoke test, Dependency Review, and CodeQL.

## Running with Liberty dev mode

Check the toolchain, start PostgreSQL, and start dev mode:

```bash
mise run setup
mise run db
mise run dev
```

The development command prepares the Maven-managed PostgreSQL driver and keeps the generated Liberty installation under `.liberty/`. Development classes use `.build/development`, while clean distributable builds use `.build/package`, so packaging does not remove or modify a running development server. No generated runtime or downloaded driver is committed.

To require the exact `200 ok` health contract and the current successful Flyway schema without rebuilding or starting services:

```bash
mise run verify-local
```

## Building Docker image

Build the production image:

```bash
mise run docker-build
```

The multi-stage build compiles production source without test compilation and produces an Open Liberty Java 25 runtime image. Run `mise run package` as the test gate; CI does so independently before accepting the production image. Maven, source files, local environment files, and credentials are not present in the runtime image. Liberty writes JSON logs to standard output and standard error.

Start the production image with Docker Compose and local PostgreSQL:

```bash
mise run docker-up
docker compose --profile application logs --follow web
```

The Compose application profile uses the local database service. Session cookies are always Secure, including during local development; use the documented `localhost` URL or HTTPS rather than a plain-HTTP non-local hostname. Set `PORT` and `APP_BASE_URL` together if port `9080` is unavailable. For example, use `PORT=9082` and `APP_BASE_URL=http://localhost:9082` in `.env`.

Confirm the runtime directly:

```bash
curl --fail http://localhost:9080/health
```

## Deploying to Railway

Use one Railway project with a PostgreSQL service named `Postgres` and a web service named `shared-calendar-web`. The committed `railway.json` owns the repeatable web build and deployment settings: the root Dockerfile, source watch paths, EU West placement, the single web replica, `/health` deployment gate, bounded restart policy, and graceful draining. Railway resource creation, database references, secrets, volumes, and domains remain environment state and are managed through Railway's API, MCP integration, CLI, or dashboard.

1. Provision a PostgreSQL 17 service named `Postgres`, attach a persistent volume at `/var/lib/postgresql/data`, and create `shared-calendar-web` in the same project and environment.
2. Deploy this repository root. Railway detects `railway.json` and the root `Dockerfile`.
3. Keep the `numReplicas` value in `railway.json` at one. Authenticated cookies and inactivity timeouts roll for 30 days, anonymous sessions expire after 30 minutes of inactivity, and all underlying HTTP sessions remain in memory.
4. Let Railway inject `PORT`; the container binds it on all interfaces.
5. Add the PostgreSQL references and application variables below.
6. Generate a Railway service domain and verify `/health` before configuring DNS.
7. Add `calendar.social` to the web service. In Namecheap Advanced DNS, create the ownership-verification `TXT` record exactly as Railway reports it and an `ALIAS` record with host `@` pointing to Railway's domain target. Remove conflicting `A`, `AAAA`, `CNAME`, `ALIAS`, or redirect records for `@` first.
8. Wait for Railway to report the domain and certificate as active, then perform the production checks below.

Connect the production web service to `Tenemo/event-calendar` with `master` as its deployment branch. Railway automatically deploys every new commit pushed or merged to `master`. Keep PR environments disabled until isolated preview deployments are intentionally introduced. The protected branch and its GitHub Actions checks gate merges; Railway's optional **Wait for CI** setting can additionally delay each post-merge deployment until the workflows triggered by that `master` push finish.

PostgreSQL service variables:

```text
PGDATA=/var/lib/postgresql/data/pgdata
POSTGRES_DB=railway
POSTGRES_USER=postgres
POSTGRES_PASSWORD=<generated high-entropy secret>
DATABASE_URL=postgresql://${{Postgres.POSTGRES_USER}}:${{Postgres.POSTGRES_PASSWORD}}@${{Postgres.RAILWAY_PRIVATE_DOMAIN}}:5432/${{Postgres.POSTGRES_DB}}
```

Keep the resolved database password only in Railway. The `DATABASE_URL` reference supports Railway's database connection tooling without copying the credential into project files.

Web service variables:

```text
PGHOST=${{Postgres.RAILWAY_PRIVATE_DOMAIN}}
PGPORT=5432
PGDATABASE=${{Postgres.POSTGRES_DB}}
PGUSER=${{Postgres.POSTGRES_USER}}
PGPASSWORD=${{Postgres.POSTGRES_PASSWORD}}
APP_TIMEZONE=Europe/Warsaw
APP_BASE_URL=https://calendar.social
APP_BOOTSTRAP_INVITE_TOKEN=
```

Set `APP_BOOTSTRAP_INVITE_TOKEN` temporarily to a generated high-entropy secret for the first registration only. After the first account is created, delete or clear the variable and redeploy; the database also records permanent bootstrap consumption.

After deployment, verify `/health`, registration, login, password change, calendar links, invitations, role changes, and event persistence. Redeploy, confirm that accounts and calendar data persist, then sign in again because HTTP sessions are intentionally in memory. Inspect logs to confirm that passwords, database credentials, calendar link tokens, and invitation tokens are absent. Railway's deployment health check is not continuous monitoring, so configure an external HTTPS uptime check for `https://calendar.social/health` before relying on the service.

Railway protects its network below the application layer, but it does not provide an application-layer WAF. The application rejects malformed calendar paths before database access, limits each client source to 300 valid-looking calendar-link requests per minute, permits at most 16 such requests to execute concurrently, and bounds source tracking to 10,000 entries. Both calendar-link and login throttles use Railway's documented `X-Real-IP` address only when Railway's automatically provided `RAILWAY_ENVIRONMENT_ID` marks the deployment and the immediate peer is in Railway's documented `100.0.0.0/8` proxy range; elsewhere they ignore that header and use the direct TCP peer. Missing, malformed, ambiguous, or untrusted client-address headers fall back to that peer. These controls make online token iteration impractical from one source and shed excess application work without disrupting a busy shared network; they cannot absorb a volumetric or large distributed attack before traffic reaches Railway. For stronger public-internet protection, proxy `calendar.social` through a service such as Cloudflare with application-layer rate limiting and bot/WAF rules, then remove Railway's generated public domain so it cannot bypass that edge. Netlify is not required.

## Registration

Registration requires an unused, unrevoked invitation token. On a brand-new empty database, create the first account by temporarily setting `APP_BOOTSTRAP_INVITE_TOKEN` to a long random value and opening:

```text
/register?token=the-random-value
```

After the first account is created, clear `APP_BOOTSTRAP_INVITE_TOKEN` and restart or redeploy the application. Bootstrap admission is claimed in the same database transaction as registration: a failed registration rolls the claim back, while the first successful registration consumes it atomically and permanently. Concurrent attempts cannot create more than one first account, and deactivating every account does not enable bootstrap again. The normal registration path uses single-use links created from `/app/invitations`.

Passwords must be between 8 and 512 characters, contain at least one uppercase letter and one digit, be nonblank, and differ from the username. They are stored as PBKDF2-HMAC-SHA256 hashes with 600,000 iterations, a 32-byte salt, and a 32-byte derived key. Plaintext passwords are never stored.

Five failed sign-in attempts for one normalized username from one client source within 15 minutes block that username/source pair for 15 minutes. Twenty-five failures from one source in the same window block further attempts from that source for 15 minutes, which limits username spraying without letting one remote client lock the account for other sources. Missing and existing usernames follow the same policy and return the same generic failure. Tracking is bounded; if every slot is occupied by an active block, authentication enters a 15-minute fail-closed saturation cooldown instead of allowing untracked attempts.

Anonymous sessions receive browser-session cookies and expire on the server after 30 minutes of inactivity. Only successful authentication extends a server session to 30 days and allows the application to issue an unconditionally Secure, HTTP-only, SameSite `Lax` persistent cookie. Authenticated application and calendar requests refresh both the cookie and the 30-day inactivity window. A server restart or redeploy clears in-memory sessions and requires reauthentication, while accounts and calendar data remain in PostgreSQL.

## Password changes

Open `/app/account-settings` while signed in. Enter the current password, then enter and confirm a different new password that follows the registration password policy. A successful change writes a new salted password hash, records a secret-free audit entry, signs out the current browser, and requires the new password at the next sign-in.

Each authenticated session records the account's database password version. Changing the password increments that version, so every other browser session is rejected before its next protected application request and must sign in with the new password. A stale session opening a canonical calendar URL is discarded and the same URL is re-evaluated with ordinary anonymous read-only permissions.

This flow requires a valid signed-in session and the current password. Forgotten-password recovery is not implemented because the application does not yet have a verified email or other recovery channel.

## Calendar roles

- `EDITOR` can view and create, edit, or delete events.
- `ADMIN` has editor permissions and can change calendar settings and manage members.

Role checks are enforced by services, not only by hidden UI controls. Every active calendar must retain at least one active admin. An admin cannot demote or remove their own membership; another admin must perform that change.

Removing an editor disables their membership and removes the calendar from their account. It does not revoke the bearer link: if public access remains enabled and the former editor retained the current URL, they can still read the calendar with exactly the same permissions as any anonymous visitor. Disable public access or regenerate the calendar link when everyone using the shared URL must lose access.

## Event times

Timed events are entered in the calendar's IANA time zone and stored with their actual UTC offsets. Nonexistent or ambiguous local times at daylight-saving transitions are rejected instead of being guessed.

For all-day events, the first and last dates shown in the form are both inclusive. The service receives those civil dates directly and persistence uses a start-inclusive, end-exclusive range from the first day's calendar-local start to the calendar-local start of the day after the last date. This preserves the intended civil dates across short, long, skipped, and repeated days. Changing a calendar's time zone renormalizes existing all-day boundaries without changing the displayed dates; an event form opened before any concurrent calendar-settings change is rejected with a reload message instead of interpreting its values in a different time zone.

## Calendar links and public access

New calendars have public access enabled and receive a bearer token made from 64 cryptographically random bits, encoded as exactly 11 unpadded Base64URL characters. `/{calendarLinkToken}` is the one canonical calendar URL: it is the address editors and admins see in their browser, and it is the address they copy and share. There is no `/calendar/` prefix. Active editors and admins see mutation controls at that URL. Anyone else with the URL receives only the read-only calendar, without signing in, so the URL should be treated as a secret.

Only exact one-segment paths containing the canonical unpadded Base64URL encoding of eight bytes enter calendar lookup. That means ten URL-safe Base64 characters followed by one of `AEIMQUYcgkosw048`; other 11-character lookalikes are rejected without a calendar database query. Valid-looking requests are source-rate-limited and globally concurrency-limited before lookup. Missing, disabled, and regenerated tokens use the same link-unavailable `404`; overload uses a generic `429` with `Retry-After`, without echoing the candidate token.

An admin can disable public access without changing the URL. Active editors and admins can continue using that same URL, while everyone else receives a `404` page explaining that the link may have been regenerated or public access may be disabled. Re-enabling public access restores read-only access at the same URL.

Any active editor or admin can use **Regenerate link** on the calendar page. Regeneration creates a new canonical URL and immediately invalidates the previous URL for everyone. Members can reach the new URL from **My calendars**; anonymous readers need to receive the new link. Regeneration does not change memberships or grant mutation access through the link.

## Invitations

Signed-in users can create registration invitations. Calendar editors and admins can create editor invitations that grant `EDITOR` membership on a selected calendar. There is no read-only membership invitation; share the calendar URL for read-only access.

Invitation links are single-use bearer secrets and expire exactly seven days after creation; callers cannot request a longer lifetime, and the database rejects one. Their creator can revoke them while unused; a calendar admin can also list and revoke unused editor invitations for that calendar. Every invitation stops working if its creator's account becomes inactive, and an editor invitation also stops working if its creator loses permission to edit that calendar. Acceptance revalidates those permissions and serializes concurrent claims, so one invitation can be consumed by exactly one account. A new user registers through the link; an existing user signs in and explicitly accepts it. Tokens are not written to application logs.

## Backup and restore

Backups use the `postgres:17` client inside Docker. No host `pg_dump` or `pg_restore` installation is needed.
Each backup is written to a unique partial file and replaces its requested destination only after `pg_dump` completes successfully, so a failed backup cannot truncate an earlier archive.

Create a timestamped local backup under `target/backups`:

```bash
mise run backup-postgres
```

Choose a specific output path:

```bash
mise run backup-postgres -- target/backups/calendar-before-upgrade.dump
```

Verify the complete backup/restore path against a fresh tmpfs PostgreSQL service. The verifier compares row counts for every application table and Flyway history, then stops the temporary database:

```bash
mise run verify-backup-restore
```

Restore replaces database objects and data. Stop the application first, keep a separate pre-restore backup, and pass the target database name a second time as explicit confirmation. Local restore refuses to continue while the Compose web service or another application on the configured local port is responding.

```bash
mise run restore-postgres -- target/backups/calendar-before-upgrade.dump calendar
```

For a remote database, set `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, and normally `PGSSLMODE=require`, then run the same backup or restore task. The tool cannot detect remote application instances, so stop them separately before restore. It uses a temporary `postgres:17` client container and passes the password through its environment, not its command line. The target database must already exist.

## Troubleshooting

### PrimeFaces class errors

The PrimeFaces dependency probably lacks the `jakarta` classifier. Check it with:

```bash
./mvnw dependency:tree -Dincludes=org.primefaces:primefaces
```

### Railway 502 or application failed to respond

Confirm that Liberty uses `host="*"`, the web service receives Railway's `PORT`, the domain targets that port, and `/health` succeeds in service logs.

### Login works locally but not in production

Confirm that `APP_BASE_URL` exactly matches the HTTPS domain, only one application replica is running, and the browser is not switching between generated and custom domains. Session cookies are always Secure and cannot be downgraded through configuration.

### Calendar link does not work

For an anonymous reader, confirm that public access is enabled and the URL has not been regenerated. The path must be exactly one root segment containing 11 Base64URL characters, with no `/calendar/` prefix. Editors and admins can open the current URL from **My calendars** even while public access is disabled. A `429` means the client source sent too many calendar-link requests and should wait for the `Retry-After` interval. Also confirm that `APP_BASE_URL` is correct for generated invitation links.

### Tables are missing

Check application startup logs for Flyway errors, verify all PostgreSQL variables, and confirm that the PostgreSQL driver exists in Liberty's shared resources. The application logs the applied Flyway version after successful startup.

### Production container does not start locally

Check `docker compose --profile application logs web postgres`, confirm that port `9080` is free or update both `PORT` and `APP_BASE_URL`, and verify that Docker Compose reports PostgreSQL as healthy.

### Backup cannot reach a remote database

Use a database endpoint reachable from Docker, not a provider-private hostname. Confirm its public host and port, firewall rules, credentials, and `PGSSLMODE` requirement without printing the password.

## Known limitations

- Railway's deployment health check is not continuous monitoring; external uptime monitoring and alerting are not configured by this repository.
- The direct Railway deployment has network-layer DDoS protection but no application-layer WAF. The in-process calendar-link controls limit brute-force and application work, while a large distributed or volumetric attack requires an upstream service such as Cloudflare.
- Backups are manual; there is no scheduled backup service or retention policy yet.
- Run one application instance because HTTP sessions are in memory, even though active authenticated cookies and inactivity timeouts roll for 30 days.
- Forgotten-password account recovery is not implemented; signed-in password changes are available from account settings.
- Recurring events, notifications, email delivery, ICS import/export, and native mobile apps are intentionally out of scope.
