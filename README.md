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
- Calendar roles are scoped to one calendar: `VIEWER`, `EDITOR`, and `ADMIN`.
- Calendars are public by default through long, random, unguessable bearer links.
- Public links are read-only and marked `noindex, nofollow`.
- Events support titles, locations, descriptions, all-day dates, and timed date ranges.
- Recurrence, notifications, email delivery, ICS import/export, and native mobile apps are outside the current scope.

## Local development

Install `mise` and Docker, then run:

```bash
mise trust
mise run setup
mise run db
mise run dev
```

The application listens on `http://localhost:9080` by default. Its liveness endpoint is `http://localhost:9080/health` and returns `ok`.

Jakarta Faces extensionless routing is enabled. Browser-facing routes include `/login`, `/register`, `/app/calendars`, `/app/calendar`, `/app/calendar-members`, `/app/calendar-settings`, and `/app/invitations`. Public calendars use `/calendar/{publicToken}`. The `.xhtml` files are internal templates, not canonical browser URLs.

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
| `APP_BASE_URL` | `http://localhost:9080` | Canonical external base URL used for invitation and public-calendar links. |
| `APP_BOOTSTRAP_INVITE_TOKEN` | blank | Optional one-time admission secret for creating the first account on an empty database. |

`APP_TIMEZONE` must be a valid IANA time zone. Invalid values stop application startup.

`APP_BASE_URL` must be an absolute HTTP or HTTPS URL without credentials, query parameters, or a fragment. Request-derived links are accepted only on loopback development hosts; production requires an explicit value.

`PGSSLMODE` is used only by the Dockerized PostgreSQL backup client. Set it to the mode required by the remote database, normally `require` for a public production endpoint.

## Database migrations

Flyway runs during application startup and owns the database schema. The application fails startup when a migration cannot be applied. Host-installed PostgreSQL client programs are not required.

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

The end-to-end suite covers registration, login, logout, calendar creation, event creation/editing/deletion, public links, token rotation, invitation acceptance, member roles, last-admin protection, validation, and read-only viewer behavior.

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

Railway deployment is a manual production operation. Use one project with a PostgreSQL service named `Postgres` and a web service named `shared-calendar-web`.

1. Connect `shared-calendar-web` to this repository and use the root `Dockerfile`.
2. Keep one web replica. The application currently uses in-memory HTTP sessions.
3. Set the health-check path to `/health`.
4. Let Railway inject `PORT`; the container binds it on all interfaces.
5. Add the PostgreSQL references and application variables below.
6. Generate a Railway service domain and set `APP_BASE_URL` to its HTTPS URL for initial verification.
7. Add the custom domain, create Railway's required DNS records, wait for certificate issuance, then change `APP_BASE_URL` to the custom HTTPS URL.

Web service variables:

```text
COOKIE_SECURE=true
PGHOST=${{ Postgres.PGHOST }}
PGPORT=${{ Postgres.PGPORT }}
PGDATABASE=${{ Postgres.PGDATABASE }}
PGUSER=${{ Postgres.PGUSER }}
PGPASSWORD=${{ Postgres.PGPASSWORD }}
APP_TIMEZONE=Europe/Warsaw
APP_BASE_URL=https://calendar.example.com
APP_BOOTSTRAP_INVITE_TOKEN=
```

After deployment, verify `/health`, registration, login, public links, invitations, role changes, event persistence, and login persistence across a redeploy. Inspect logs to confirm that passwords, database credentials, public tokens, and invitation tokens are absent.

## Registration

Registration requires an unused, unrevoked invitation token. On a brand-new empty database, create the first account by temporarily setting `APP_BOOTSTRAP_INVITE_TOKEN` to a long random value and opening:

```text
/register?token=the-random-value
```

After the first account is created, clear `APP_BOOTSTRAP_INVITE_TOKEN` and restart or redeploy the application. The normal registration path uses single-use links created from `/app/invitations`.

Passwords must be between 14 and 512 characters, nonblank, and different from the username. They are stored as PBKDF2-HMAC-SHA256 hashes with 600,000 iterations, a 32-byte salt, and a 32-byte derived key. Plaintext passwords are never stored.

## Calendar roles

- `VIEWER` can view an assigned calendar while signed in but cannot mutate events.
- `EDITOR` can view and create, edit, or delete events.
- `ADMIN` has editor permissions and can change calendar settings and manage members.

Role checks are enforced by services, not only by hidden UI controls. Every active calendar must retain at least one active admin. An admin cannot demote or remove their own membership; another admin must perform that change.

## Public calendar links

New calendars have public access enabled and receive a random bearer token. Anyone with `/calendar/{publicToken}` can view the calendar without signing in, so the URL should be treated as a secret.

Public pages are read-only and return a generic `404` for invalid, disabled, or rotated tokens. Rotating a public link immediately invalidates the previous URL. Disabling public access preserves the token but makes the public route unavailable until access is re-enabled.

## Invitations

Signed-in users can create registration invitations. Calendar editors and admins can create editor invitations that also grant `EDITOR` membership on a selected calendar.

Invitation links are single-use bearer secrets. They can be revoked by their creator while unused. A new user registers through the link; an existing user signs in and explicitly accepts it. Tokens are not written to application logs.

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

### Public link does not work

Confirm that public access is enabled, the token has not been rotated, `APP_BASE_URL` is correct, and the route starts with `/calendar/` followed by exactly one token segment.

### Tables are missing

Check application startup logs for Flyway errors, verify all PostgreSQL variables, and confirm that the PostgreSQL driver exists in Liberty's shared resources. The application logs the applied Flyway version after successful startup.

### Production container does not start locally

Check `docker compose --profile application logs web postgres`, confirm that port `9080` is free or update both `PORT` and `APP_BASE_URL`, and verify that Docker Compose reports PostgreSQL as healthy.

### Backup cannot reach a remote database

Use a database endpoint reachable from Docker, not a provider-private hostname. Confirm its public host and port, firewall rules, credentials, and `PGSSLMODE` requirement without printing the password.

## Known limitations

- Railway deployment, generated-domain checks, custom-domain DNS, and production redeploy persistence must be completed as a separate operational step.
- Backups are manual; there is no scheduled backup service or retention policy yet.
- Run one application instance because HTTP sessions are in memory.
- Password change, account recovery, and login throttling are not implemented.
- Recurring events, notifications, email delivery, ICS import/export, and native mobile apps are intentionally out of scope.
