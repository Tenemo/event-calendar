# M3: production readiness

Use this milestone to make the app packageable, deployable, recoverable, documented, and safe enough for real personal use.

## Implementation status

The repository implementation was completed and locally verified on 2026-07-10. Railway project creation, deployment, generated-domain validation, custom-domain DNS, HTTPS validation, and persistence checks across a real production redeploy are explicitly deferred to a separate operational task.

Implemented repository deliverables:

1. Multi-stage Java 25 and Open Liberty production image.
2. Opt-in Compose application profile using the production image and Dockerized PostgreSQL.
3. JSON Liberty logs on standard output and standard error.
4. Portable `mise` and Java commands for image build, container startup, backup, guarded restore, and isolated restore verification.
5. Fresh tmpfs PostgreSQL restore-verification service with schema and row-count comparison.
6. Maven, database-backed browser tests, production container smoke, Dependency Review, and CodeQL configuration.
7. Complete public operating runbook in `README.md`.

Local verification evidence:

1. `mise run docker-build` produced `shared-calendar:local` from the repository Dockerfile.
2. The production container ran on local port `9082` with `COOKIE_SECURE=false` and returned `200 ok` from `/health`.
3. The runtime image contained the WAR and PostgreSQL driver but no Maven installation or build source directory.
4. Liberty emitted JSON console logs without passwords, public tokens, or invitation tokens.
5. Flyway migrations 1 through 4 were present and successful.
6. Invalid public calendar routes returned `404` and included `noindex`.
7. The local session cookie was `HttpOnly`, `SameSite=Lax`, and correctly omitted `Secure` for HTTP.
8. `mise run verify-backup-restore` restored a custom-format dump into a fresh tmpfs PostgreSQL service and matched all application-table and Flyway-history row counts.
9. `mise run e2e` against the production container passed 69 unit tests and all 4 browser workflows.

## Milestone checklist

Outcome: the repository is packaged, Railway-deployable, recoverable from backup, and documented for real use. Live Railway acceptance remains deferred.

- [x] Build the production Docker image.
- [x] Run the app container locally against Dockerized PostgreSQL.
- [x] Confirm health, registration, login, calendar creation, public links, invite links, event CRUD, member management, environment variables, and `COOKIE_SECURE=false` locally.
- [x] Add Dockerized backup and restore commands.
- [x] Test backup and restore against a fresh local Docker Compose database.
- [ ] Create the Railway project, PostgreSQL service, and web service. Deferred by the owner.
- [ ] Configure Railway variables and deploy. Deferred by the owner.
- [ ] Confirm generated Railway domain, then custom domain and HTTPS. Deferred by the owner.
- [ ] Verify invitation, account, calendar, editor, and event persistence across a Railway redeploy. Deferred by the owner.
- [x] Update README with setup, deployment, environment variables, roles, registration, public links, invitations, backup/restore, troubleshooting, and known limitations.
- [x] Extend GitHub PR checks with Docker build coverage and available security checks.

Verification commands:

```bash
mise run package
mise run docker-build
mise run docker-up
mise run verify-backup-restore
mise run e2e
```

## Acceptance status

| Criterion | Status |
| --- | --- |
| Docker image builds from repository source | Verified locally |
| Runtime does not need Maven | Verified locally |
| Application logs to stdout/stderr | Verified locally with JSON console logs |
| No secrets are baked into the image | Verified from explicit Docker build inputs and runtime inspection |
| Dockerized backup and restore work without host PostgreSQL clients | Verified locally |
| Railway health and custom HTTPS domain | Deferred |
| Invitation-only registration and links in production | Deferred; verified in the local production container |
| Login, public links, invite links, and events persist after redeploy | Deferred |
| Railway logs exclude passwords and bearer tokens | Deferred; verified in local production-container logs |
| README contains the complete operating runbook | Implemented |
| PR definitions cover build, database/browser tests, container smoke, and security checks | Implemented; remote workflow execution pending push |

## Docker build

The root `Dockerfile` uses a disposable `eclipse-temurin:25-jdk` build stage and the Open Liberty `kernel-slim-java25-openj9-ubi-minimal` runtime. It installs only build-time wrapper prerequisites, packages the WAR with tests skipped because tests run in a separate required build, copies the Maven-managed PostgreSQL driver into Liberty shared resources, installs the declared Jakarta features, and does not require a database during image construction.

The `.dockerignore` excludes source-control state, local environments, build output, private planning material, logs, and local scratch files. The runtime receives only Liberty configuration, container logging configuration, the PostgreSQL driver, and the WAR.

Local image and container commands:

```bash
mise run docker-build
mise run docker-up
docker compose --profile application logs --follow web
```

The Compose `application` profile keeps PostgreSQL and the web service on one Docker network, avoiding host-specific database routing.

## GitHub PR checks

The PR workflow now provides:

1. Maven Wrapper clean test and package build.
2. PrimeFaces Jakarta classifier assertion.
3. PostgreSQL-backed Liberty and Playwright workflows.
4. Production Docker image build and container smoke against PostgreSQL.
5. Health, home, invalid-public-route, and Flyway checks on the production container.
6. Dependency Review for pull requests, failing on new moderate-or-higher vulnerable dependencies.
7. CodeQL Java analysis on pull requests, pushes to `master`, and a weekly schedule.

PR workflows do not deploy to Railway and do not require Railway secrets.

## Railway deployment plan

Create one Railway project with two services:

```text
shared-calendar-web
Postgres
```

The web service uses the repository root `Dockerfile`. PostgreSQL is a Railway PostgreSQL service.

Set these variables on the web service:

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

Railway injects `PORT`; do not replace it with a fixed value. Adjust the `Postgres` namespace if the PostgreSQL service uses another name.

Railway requirements:

1. The container must listen on `0.0.0.0`.
2. The container must listen on the injected `PORT`.
3. The Dockerfile must be named `Dockerfile` with capital `D` at the repo root unless you configure a custom path.

## Custom domain

For a subdomain such as `calendar.example.com`:

1. Open the Railway web service settings.
2. Add a custom domain.
3. Add the provided `CNAME` record in DNS.
4. Add the provided `TXT` record in DNS.
5. Wait for Railway verification.
6. Confirm HTTPS works.
7. Update `APP_BASE_URL` to the custom domain.
8. Set `COOKIE_SECURE=true`.

Do not skip the TXT record; Railway uses it to verify ownership before routing traffic.

## Backup and restore

Backup and restore use portable Java orchestration and Dockerized PostgreSQL 17 clients. No host `psql`, `pg_dump`, or `pg_restore` installation is required.
Backup output is staged in a unique partial file and atomically replaces the requested destination only after a successful non-empty dump.

```bash
mise run backup-postgres
mise run backup-postgres -- target/backups/calendar-before-upgrade.dump
mise run restore-postgres -- target/backups/calendar-before-upgrade.dump calendar
mise run verify-backup-restore
```

The restore command requires the configured database name as an explicit second argument before it runs `pg_restore --clean --if-exists --single-transaction --exit-on-error`. The archive is validated before restore. The application must be stopped before a real restore.

Local operations execute clients inside the Compose PostgreSQL service. Remote operations use a temporary `postgres:17` client container with `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, and optional `PGSSLMODE`; the password is passed through the container environment rather than the command line.

`mise run verify-backup-restore` force-recreates a dedicated PostgreSQL service whose data directory is tmpfs, restores the local backup, compares counts for every application table and Flyway history, and stops the verification service. This workflow passed locally.

## README requirements

README must include:

```text
# Shared calendar

## Stack
## Product model
## Local development
## Environment variables
## Database migrations
## Running tests
## Running with Liberty dev mode
## Building Docker image
## Deploying to Railway
## Registration
## Calendar roles
## Public calendar links
## Invitations
## Backup and restore
## Troubleshooting
## Known limitations
```

Troubleshooting entries to include:

### PrimeFaces class errors

Likely cause: missing `jakarta` classifier.

Check:

```bash
./mvnw dependency:tree | grep primefaces
```

### Railway 502 / application failed to respond

Likely causes:

1. App not bound to `host="*"`.
2. App not using `PORT`.
3. Wrong target port in Railway domain settings.

### Login works locally but not on production

Likely causes:

1. Cookie settings.
2. `COOKIE_SECURE` wrong for environment.
3. Domain/HTTPS mismatch.
4. Session lost after redeploy.

### Public link does not work

Likely causes:

1. Calendar public access disabled.
2. Token was rotated.
3. Wrong `APP_BASE_URL`.
4. Route mapping mismatch.

### Tables missing

Likely causes:

1. Flyway did not run.
2. Wrong database variables.
3. PostgreSQL driver not copied into Liberty config resources.
