# Shared calendar

Shared calendar is a server-rendered web application for event calendars shared among friends. It is intended for kayaking plans, birthdays, trips, and similar coordination without notifications or a native mobile app.

## Stack

- Java 25 runtime with Java 21 source compatibility
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
- Calendars are public by default through long, random, unguessable bearer links.
- Public links are read-only and marked `noindex, nofollow`.
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

Jakarta Faces extensionless routing is enabled. Browser-facing routes include `/login`, `/register`, `/app/calendars`, `/app/calendar-members`, `/app/calendar-settings`, and `/app/invitations`. Every calendar uses `/calendar/{calendarToken}` as its canonical URL for editors, admins, and anonymous readers. The `.xhtml` files are internal templates, not canonical browser URLs.

## Environment variables

Copy `.env.example` to `.env` for local development. Do not commit `.env`.

| Variable | Local default | Purpose |
| --- | --- | --- |
| `PORT` | `9080` | Liberty HTTP port, or the host port used by the Compose web service. Railway injects this value. |
| `HTTPS_PORT` | `9443` | Optional local Liberty HTTPS listener. Railway terminates HTTPS at its proxy. |
| `COOKIE_SECURE` | `false` | Set to `true` whenever the external application URL uses HTTPS. |
| `PGHOST` | `localhost` | PostgreSQL host. The Compose web container uses `postgres`. |
| `PGPORT` | `5432` | PostgreSQL port. |
| `PGDATABASE` | `calendar` | PostgreSQL database name. |
| `PGUSER` | `calendar` | PostgreSQL user. |
| `PGPASSWORD` | `calendar` | PostgreSQL password. Use a generated secret outside local development. |
| `APP_TIMEZONE` | `Europe/Warsaw` | Default IANA time zone assigned to new calendars. |
| `APP_BASE_URL` | `http://localhost:9080` | Canonical external base URL used for invitation and calendar links. |
| `APP_BOOTSTRAP_INVITE_TOKEN` | blank | Optional one-time admission secret for creating the first account on a database that has never contained an account. |

`APP_TIMEZONE` must be a valid IANA time zone. Invalid values stop application startup.

`APP_BASE_URL` must be an absolute HTTP or HTTPS URL without credentials, query parameters, or a fragment. Request-derived links are accepted only on loopback development hosts; production requires an explicit value.

`PGSSLMODE` is used only by the Dockerized PostgreSQL backup client. Set it to the mode required by the remote database, normally `require` for a public production endpoint.

## Database migrations

Flyway runs during application startup and owns the database schema. The application fails startup when a migration cannot be applied. The current schema is migration version 8. Migration 8 removes existing read-only memberships rather than promoting them to editor access, then restricts calendar memberships to `EDITOR` and `ADMIN`. Host-installed PostgreSQL client programs are not required.

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

For browser tests, keep `mise run dev` running in one terminal, then run:

```bash
mise run install-playwright
mise run e2e
```

The automated suite contains 122 unit tests and 18 primary browser scenarios covering registration, login, logout, calendar creation, event creation/editing/deletion, canonical calendar links, public-access disabling, link regeneration, invitation acceptance, editor removal, last-admin protection, validation, and anonymous read-only behavior. One additional isolated browser scenario builds the production image and verifies bootstrap-registration rollback and concurrency against a separate application container and tmpfs PostgreSQL database on non-default ports.

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

Prepare Liberty's shared PostgreSQL driver, start PostgreSQL, and start dev mode:

```bash
mise run setup
mise run db
mise run dev
```

The setup task copies the Maven-managed PostgreSQL driver into generated Liberty resources under `target/`. No downloaded driver is committed.

To check an already-running application and its database without rebuilding:

```bash
mise run verify-running-app
```

## Building Docker image

Build the production image:

```bash
mise run docker-build
```

The multi-stage build compiles the WAR with Maven and produces an Open Liberty Java 25 runtime image. Maven, source files, local environment files, and credentials are not present in the runtime image. Liberty writes JSON logs to standard output and standard error.

Start the production image with Docker Compose and local PostgreSQL:

```bash
mise run docker-up
docker compose --profile application logs --follow web
```

The Compose application profile uses `COOKIE_SECURE=false` and the local database service. Set `PORT` and `APP_BASE_URL` together if port `9080` is unavailable. For example, use `PORT=9082` and `APP_BASE_URL=http://localhost:9082` in `.env`.

Confirm the runtime directly:

```bash
curl --fail http://localhost:9080/health
```

## Deploying to Railway

Use one Railway project with a PostgreSQL service named `Postgres` and a web service named `shared-calendar-web`. The committed `railway.json` owns the repeatable web build and deployment settings: the root Dockerfile, source watch paths, EU West placement, the single web replica, `/health` deployment gate, bounded restart policy, and graceful draining. Railway resource creation, database references, secrets, volumes, and domains remain environment state and are managed through Railway's API, MCP integration, CLI, or dashboard.

1. Provision a PostgreSQL 17 service named `Postgres`, attach a persistent volume at `/var/lib/postgresql/data`, and create `shared-calendar-web` in the same project and environment.
2. Deploy this repository root. Railway detects `railway.json` and the root `Dockerfile`.
3. Keep the `numReplicas` value in `railway.json` at one. Authenticated cookies and inactivity timeouts roll for 30 days, but the underlying HTTP sessions remain in memory.
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
COOKIE_SECURE=true
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

After deployment, verify `/health`, registration, login, public links, invitations, role changes, and event persistence. Redeploy, confirm that accounts and calendar data persist, then sign in again because HTTP sessions are intentionally in memory. Inspect logs to confirm that passwords, database credentials, public tokens, and invitation tokens are absent. Railway's deployment health check is not continuous monitoring, so configure an external HTTPS uptime check for `https://calendar.social/health` before relying on the service.

## Registration

Registration requires an unused, unrevoked invitation token. On a brand-new empty database, create the first account by temporarily setting `APP_BOOTSTRAP_INVITE_TOKEN` to a long random value and opening:

```text
/register?token=the-random-value
```

After the first account is created, clear `APP_BOOTSTRAP_INVITE_TOKEN` and restart or redeploy the application. Bootstrap admission is claimed in the same database transaction as registration: a failed registration rolls the claim back, while the first successful registration consumes it atomically and permanently. Concurrent attempts cannot create more than one first account, and deactivating every account does not enable bootstrap again. The normal registration path uses single-use links created from `/app/invitations`.

Passwords must be between 8 and 512 characters, contain at least one uppercase letter and one digit, be nonblank, and differ from the username. They are stored as PBKDF2-HMAC-SHA256 hashes with 600,000 iterations, a 32-byte salt, and a 32-byte derived key. Plaintext passwords are never stored.

Five failed sign-in attempts for one normalized username from one client source within 15 minutes block that username/source pair for 15 minutes. Twenty-five failures from one source in the same window block further attempts from that source for 15 minutes, which limits username spraying without letting one remote client lock the account for other sources. Missing and existing usernames follow the same policy and return the same generic failure.

Authenticated sessions use an HTTP-only, SameSite `Lax` cookie that is secure when `COOKIE_SECURE=true`. Authenticated application and calendar requests refresh the persistent cookie's rolling 30-day lifetime, and the server invalidates a session after 30 days of inactivity. A server restart or redeploy clears in-memory sessions and requires reauthentication, while accounts and calendar data remain in PostgreSQL.

## Calendar roles

- `EDITOR` can view and create, edit, or delete events.
- `ADMIN` has editor permissions and can change calendar settings and manage members.

Role checks are enforced by services, not only by hidden UI controls. Every active calendar must retain at least one active admin. An admin cannot demote or remove their own membership; another admin must perform that change.

Removing an editor disables their membership and removes the calendar from their account. It does not revoke the bearer link: if public access remains enabled and the former editor retained the current URL, they can still read the calendar with exactly the same permissions as any anonymous visitor. Disable public access or regenerate the calendar link when everyone using the shared URL must lose access.

## Event times

Timed events are entered in the calendar's IANA time zone and stored with their actual UTC offsets. Nonexistent or ambiguous local times at daylight-saving transitions are rejected instead of being guessed.

For all-day events, the first and last dates shown in the form are both inclusive. Persistence uses a start-inclusive, end-exclusive range from the first day's calendar-local start to the calendar-local start of the day after the last date. This preserves the intended civil dates across short, long, skipped, and repeated days, and changing a calendar's time zone renormalizes existing all-day boundaries without changing the displayed dates.

## Calendar links and public access

New calendars have public access enabled and receive a random bearer token. `/calendar/{calendarToken}` is the one canonical calendar URL: it is the address editors and admins see in their browser, and it is the address they copy and share. Active editors and admins see mutation controls at that URL. Anyone else with the URL receives only the read-only calendar, without signing in, so the URL should be treated as a secret.

An admin can disable public access without changing the URL. Active editors and admins can continue using that same URL, while everyone else receives a `404` page explaining that the link may have been regenerated or public access may be disabled. Re-enabling public access restores read-only access at the same URL.

Any active editor or admin can use **Regenerate link** on the calendar page. Regeneration creates a new canonical URL and immediately invalidates the previous URL for everyone. Members can reach the new URL from **My calendars**; anonymous readers need to receive the new link. Regeneration does not change memberships or grant mutation access through the link.

## Invitations

Signed-in users can create registration invitations. Calendar editors and admins can create editor invitations that grant `EDITOR` membership on a selected calendar. There is no read-only membership invitation; share the calendar URL for read-only access.

Invitation links are single-use bearer secrets and expire after seven days. Their creator can revoke them while unused; a calendar admin can also list and revoke unused editor invitations for that calendar. Every invitation stops working if its creator's account becomes inactive, and an editor invitation also stops working if its creator loses permission to edit that calendar. Acceptance revalidates those permissions and serializes concurrent claims, so one invitation can be consumed by exactly one account. A new user registers through the link; an existing user signs in and explicitly accepts it. Tokens are not written to application logs.

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

Restore replaces database objects and data. Stop the application first, keep a separate pre-restore backup, and pass the target database name a second time as explicit confirmation:

```bash
mise run restore-postgres -- target/backups/calendar-before-upgrade.dump calendar
```

For a remote database, set `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, and normally `PGSSLMODE=require`, then run the same backup or restore task. The tool uses a temporary `postgres:17` client container and passes the password through its environment, not its command line. The target database must already exist.

## Troubleshooting

### PrimeFaces class errors

The PrimeFaces dependency probably lacks the `jakarta` classifier. Check it with:

```bash
./mvnw dependency:tree -Dincludes=org.primefaces:primefaces
```

### Railway 502 or application failed to respond

Confirm that Liberty uses `host="*"`, the web service receives Railway's `PORT`, the domain targets that port, and `/health` succeeds in service logs.

### Login works locally but not in production

Confirm that `COOKIE_SECURE=true`, `APP_BASE_URL` exactly matches the HTTPS domain, only one application replica is running, and the browser is not switching between generated and custom domains.

### Calendar link does not work

For an anonymous reader, confirm that public access is enabled and the URL has not been regenerated. The route must start with `/calendar/` followed by exactly one token segment. Editors and admins can open the current URL from **My calendars** even while public access is disabled. Also confirm that `APP_BASE_URL` is correct for generated invitation links.

### Tables are missing

Check application startup logs for Flyway errors, verify all PostgreSQL variables, and confirm that the PostgreSQL driver exists in Liberty's shared resources. The application logs the applied Flyway version after successful startup.

### Production container does not start locally

Check `docker compose --profile application logs web postgres`, confirm that port `9080` is free or update both `PORT` and `APP_BASE_URL`, and verify that Docker Compose reports PostgreSQL as healthy.

### Backup cannot reach a remote database

Use a database endpoint reachable from Docker, not a provider-private hostname. Confirm its public host and port, firewall rules, credentials, and `PGSSLMODE` requirement without printing the password.

## Known limitations

- Railway's deployment health check is not continuous monitoring; external uptime monitoring and alerting are not configured by this repository.
- Backups are manual; there is no scheduled backup service or retention policy yet.
- Run one application instance because HTTP sessions are in memory, even though active authenticated cookies and inactivity timeouts roll for 30 days.
- Password change and account recovery are not implemented.
- Recurring events, notifications, email delivery, ICS import/export, and native mobile apps are intentionally out of scope.
