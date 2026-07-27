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

- Registration is invitation-only, and the registration form is shown only after the bearer link passes a non-consuming availability check.
- Every registered user can create multiple calendars.
- A calendar creator receives that calendar's `ADMIN` role.
- Calendar roles are scoped to one calendar: `EDITOR` and `ADMIN`.
- Calendar invitations grant `EDITOR`; read-only access uses the calendar's bearer link rather than a membership role. Registration-only invitations create new accounts and cannot be applied to an account that is already signed in.
- Signed-in users can change their own password from account settings; doing so invalidates every existing session.
- Calendars are public by default through compact, random bearer links.
- Calendar links with public access are read-only and marked `noindex, nofollow`.
- Events support titles, locations, descriptions, inclusive all-day date ranges, and timed date ranges. All-day dates are normalized in the calendar's IANA time zone instead of assuming every day is 24 hours.
- Calendar pages load events in deterministic 50-event pages so a large calendar cannot create an unbounded database result or response; **Load more events** retrieves the next page. Each page carries a constant-size event revision. If events change between page requests, the view restarts from the first page before continuing so an event whose start time moved across the cursor cannot be omitted or duplicated. A change committed after a page's final revision check belongs to the next logical snapshot and is detected before the following page is loaded.
- Recurrence, notifications, email delivery, ICS import/export, and native mobile apps are outside the current scope.

## Local development

Install `mise` and Docker, then run:

```bash
mise trust
mise run setup
mise run db
mise run dev
```

Open the application at `https://localhost:9443` by default. Liberty generates a local development certificate, so the browser may ask you to accept it the first time. The database-aware health endpoint remains available at `http://localhost:9080/health`; it returns `200 ok` only when PostgreSQL is reachable and `503 unavailable` for SQL, data-source, driver, or invalid-connection results. Concurrent health requests share one database validation per application instance, and both outcomes are cached for one second so monitoring cannot consume the connection pool with duplicate probes. Failed validations emit only a fixed event name and failure type, at most once per five minutes, without connection details or exception messages.

Jakarta Faces extensionless routing is enabled. Browser-facing routes include `/login`, `/register`, `/app/calendars`, `/app/account-settings`, `/app/calendar-members`, `/app/calendar-settings`, and `/app/invitations`. Every calendar uses one 11-character token directly at the root, such as `https://calendar.social/Abc_123-xY0`, as its canonical URL for editors, admins, and anonymous readers. The root 11-character Base64URL namespace is reserved for calendars. The `.xhtml` files are internal templates, not canonical browser URLs.

## Environment variables

Copy `.env.example` to `.env` for local development. Do not commit `.env`.

| Variable | Local default | Purpose |
| --- | --- | --- |
| `PORT` | `9080` | Liberty HTTP health and proxy port, or the host port used by the Compose web service. Railway injects this value. |
| `HTTPS_PORT` | `9443` | Local HTTPS browser port. Railway terminates HTTPS at its proxy. |
| `PGHOST` | `localhost` | PostgreSQL host. The Compose web container uses `postgres`. |
| `PGPORT` | `5432` | PostgreSQL port. |
| `PGDATABASE` | `calendar` | PostgreSQL database name. |
| `PGUSER` | `calendar` | PostgreSQL user. |
| `PGPASSWORD` | `calendar` | PostgreSQL password. Use a generated secret outside local development. |
| `APP_TIMEZONE` | `Europe/Warsaw` | Default IANA time zone assigned to new calendars. |
| `APP_BASE_URL` | `https://localhost:9443` | Canonical external base URL used for invitation and calendar links. |
| `APP_LTPA_KEYS_PASSWORD` | `local-development-only` | Stable password used by Liberty to protect its generated authentication signing keys. |
| `APP_BOOTSTRAP_INVITE_TOKEN` | blank | Optional 43-80 character unpadded Base64URL admission secret for creating the first account on a database that has never contained an account. |
| `APP_DESCRIPTION_MIGRATION_MAINTENANCE_ACKNOWLEDGED` | blank | Temporary production-only acknowledgement for the write-maintenance window required when an existing schema below version 19 is upgraded through the description constraints. |

`APP_TIMEZONE` must be an identifier supported by Java's IANA time-zone database. Invalid values stop application startup.

`APP_BASE_URL` must be an absolute HTTP or HTTPS URL without credentials, query parameters, or a fragment. A malformed configured value stops application startup. Railway startup also fails when the value is missing, blank, or not HTTPS. Request-derived links are accepted only on loopback hosts. Use HTTPS for browser-facing local development because authenticated cookies are unconditionally Secure; the HTTP listener is intended for local health checks and Railway's private proxy hop.

`APP_LTPA_KEYS_PASSWORD` must be set to a generated high-entropy value of at least 32 characters on Railway. Keep it stable across application restarts so Liberty can decrypt its existing authentication signing keys. Missing, blank, or shorter Railway values stop application startup. The committed local value is deterministic only for disposable development and verification environments; never reuse it in production. Do not log or commit the production value.

`APP_BOOTSTRAP_INVITE_TOKEN`, when set, must contain only Base64URL characters and be 43 through 80 characters long. Generate at least 32 cryptographically random bytes and encode them as unpadded Base64URL; 32 bytes produce a 43-character token. A malformed nonblank value stops application startup.

`APP_DESCRIPTION_MIGRATION_MAINTENANCE_ACKNOWLEDGED` must normally remain blank. Set it to `true` only for the production maintenance procedure below. A nonempty Railway production database whose current successful migration is below version 19 refuses to start without this acknowledgement. Fresh databases, nonproduction environments, and databases already at version 19 or later do not require it.

## Database migrations

Flyway runs during application startup and owns the database schema. The application fails startup when a migration cannot be applied. The current schema is migration version 28. Migration 8 removes existing read-only memberships rather than promoting them to editor access and restricts calendar memberships to `EDITOR` and `ADMIN`. Migration 9 adds the password version used to invalidate older authenticated sessions after a password change. Migration 10 replaces every existing calendar bearer token with the compact format, so deploying it invalidates every previously shared calendar URL once. Migration 11 caps every existing invitation at seven days after creation and adds a database constraint that prevents longer lifetimes. Migration 12 audits existing calendar time zones without changing them and fails closed if any stored value is not an exact identifier supported by Java's IANA time-zone database. If that audit fails, inspect the distinct `calendar.timezone` values, back up the database, map each unsupported value to its intended supported region identifier, and restart the application so Flyway retries the migration.

The committed form of migration 10 has always installed the canonical bearer-token constraint. Migrations 13 through 15 defensively re-apply it for databases that encountered an earlier development revision: migration 13 adds the constraint without scanning existing rows, migration 14 validates it while normal reads and writes can continue, and migration 15 performs the short timeout-bounded constraint-name swap. They are semantic no-ops for databases built only from committed revisions and remain immutable migration history.

Migrations 16 and 17 add database limits that enforce the application's 4,000 UTF-16-code-unit maximum for calendar and event descriptions on new writes without first scanning existing rows. Migrations 18 and 19 validate the calendar and event constraints against existing data in separate transactions. Because an old Railway deployment can continue accepting writes while its replacement starts, an existing production database below version 19 requires the explicit write-maintenance procedure below. If description validation fails, back up the database and shorten the over-limit descriptions before retrying startup.

Migrations 20 through 25 each build one event or invitation query index with `CREATE INDEX CONCURRENTLY`. Migrations 26 through 28 remove the replaced prefix indexes one at a time with `DROP INDEX CONCURRENTLY`, only after every replacement exists. Each of these migrations is nontransactional, has bounded lock and statement waits, and retains normal table writes while PostgreSQL builds the index, although the build still consumes database resources and briefly takes lightweight table locks. Flyway uses a PostgreSQL session-level migration lock so only one migrator owns this nontransactional sequence. Every build first removes an orphaned index with its own target name, making a repaired migration safe to retry after an interrupted concurrent build.

Audit existing descriptions before deploying these migrations. This query returns every row that exceeds the application limit; no rows is the expected result:

```sql
select
    'calendar' as source_table,
    id,
    char_length(description)
        + regexp_count(description, '[\U00010000-\U0010FFFF]') as utf_16_code_units
from calendar
where description is not null
    and char_length(description)
        + regexp_count(description, '[\U00010000-\U0010FFFF]') > 4000
union all
select
    'calendar_event' as source_table,
    id,
    char_length(description)
        + regexp_count(description, '[\U00010000-\U0010FFFF]') as utf_16_code_units
from calendar_event
where description is not null
    and char_length(description)
        + regexp_count(description, '[\U00010000-\U0010FFFF]') > 4000
order by source_table, id;
```

For the first production deployment that upgrades an existing database from any version below 19:

1. Back up the database and verify that the backup completed.
2. Scale `shared-calendar-web` to zero and confirm that no application instance or other writer remains connected. This is the write-maintenance window; auditing while the previous deployment can still write is not sufficient.
3. Run the description audit above against production. Shorten every returned description to at most 4,000 UTF-16 code units, rerun the audit, and continue only when it returns no rows.
4. Set `APP_DESCRIPTION_MIGRATION_MAINTENANCE_ACKNOWLEDGED=true` on the production web service.
5. Deploy the new revision and start one web replica so that only the new revision runs against the database. Wait for `/health` to return `200 ok`, confirm the expected deployment revision, and confirm that `flyway_schema_history` ends at successful version 28 with no failed row.
6. Clear `APP_DESCRIPTION_MIGRATION_MAINTENANCE_ACKNOWLEDGED`, redeploy the same revision, verify `/health` and the exact revision again, and leave the service at its normal single replica. Do not leave the acknowledgement enabled for later deployments.

### Interrupted concurrent-index recovery

If startup stops in migrations 20 through 28, keep the application scaled to zero, preserve a database backup, and correct the cause first, such as insufficient storage, a conflicting long transaction, or lost connectivity. Inspect both Flyway history and the target indexes:

```sql
select installed_rank, version, description, script, success
from flyway_schema_history
where not success
order by installed_rank;

select
    index_record.relname as index_name,
    index_state.indisready,
    index_state.indisvalid
from pg_index index_state
join pg_class index_record on index_record.oid = index_state.indexrelid
join pg_namespace namespace_record on namespace_record.oid = index_record.relnamespace
where namespace_record.nspname = current_schema()
    and index_record.relname in (
        'idx_calendar_event_calendar_start_id',
        'idx_calendar_event_all_day_calendar',
        'idx_app_invitation_created_by_user_created_at_id',
        'idx_app_invitation_calendar_created_at_id',
        'idx_app_invitation_registration_creator_expires',
        'idx_app_invitation_editor_calendar_expires'
    )
order by index_record.relname;
```

If there is no failed Flyway row, retry the deployment after correcting the cause; the pending migration removes its own orphaned target before rebuilding it. If and only if the first query returns exactly one failed row whose version and script match one pair below, remove that failed history row with this narrow repair. The block aborts without changing history for every other state:

```sql
do $repair$
declare
    failed_migration_count integer;
    recognized_failed_migration_count integer;
begin
    select count(*) into failed_migration_count
    from flyway_schema_history
    where not success;

    select count(*) into recognized_failed_migration_count
    from flyway_schema_history migration_history
    join (values
        ('20', 'V20__create_event_cursor_index.sql'),
        ('21', 'V21__create_all_day_event_index.sql'),
        ('22', 'V22__create_invitation_creator_history_index.sql'),
        ('23', 'V23__create_invitation_calendar_history_index.sql'),
        ('24', 'V24__create_registration_invitation_capacity_index.sql'),
        ('25', 'V25__create_editor_invitation_capacity_index.sql'),
        ('26', 'V26__drop_replaced_event_start_index.sql'),
        ('27', 'V27__drop_replaced_invitation_creator_index.sql'),
        ('28', 'V28__drop_replaced_invitation_calendar_index.sql')
    ) as recognized_migration(version, script)
        on recognized_migration.version = migration_history.version
        and recognized_migration.script = migration_history.script
    where not migration_history.success;

    if failed_migration_count <> 1 or recognized_failed_migration_count <> 1 then
        raise exception 'Expected exactly one recognized failed migration from version 20 through 28.';
    end if;

    delete from flyway_schema_history migration_history
    using (values
        ('20', 'V20__create_event_cursor_index.sql'),
        ('21', 'V21__create_all_day_event_index.sql'),
        ('22', 'V22__create_invitation_creator_history_index.sql'),
        ('23', 'V23__create_invitation_calendar_history_index.sql'),
        ('24', 'V24__create_registration_invitation_capacity_index.sql'),
        ('25', 'V25__create_editor_invitation_capacity_index.sql'),
        ('26', 'V26__drop_replaced_event_start_index.sql'),
        ('27', 'V27__drop_replaced_invitation_creator_index.sql'),
        ('28', 'V28__drop_replaced_invitation_calendar_index.sql')
    ) as recognized_migration(version, script)
    where not migration_history.success
        and recognized_migration.version = migration_history.version
        and recognized_migration.script = migration_history.script;
end
$repair$;
```

Retry the deployment. The creation migrations concurrently remove any valid or invalid orphan with the target name before rebuilding it; the removal migrations use `IF EXISTS`. After startup, require successful history through version 28, no failed rows, and `indisready = true` plus `indisvalid = true` for all six replacement indexes. Never use this repair for a failed migration outside versions 20 through 28 or for multiple failed rows; investigate that state separately instead of deleting history.

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
mise run end-to-end
```

Run the same Lighthouse performance budget used on pull requests against the application at `APP_BASE_URL` (or `https://localhost:9443` by default):

```bash
mise run lighthouse
```

Lighthouse makes three simulated-mobile measurements of both the public landing and sign-in pages. It gates each page's median value for the performance score, first and largest contentful paint, total blocking time, and cumulative layout shift. HTML and JSON reports are written to `.build/lighthouse`.

Set `BROWSER` to `firefox` or `webkit` when intentionally running another supported browser. Pull requests run the complete suite in Chromium and focused compatibility journeys in Firefox and WebKit. The automated suite covers registration, sign-in, sign-out, password changes and session revocation, calendar creation, event creation/editing/deletion, compact canonical calendar links, public-access disabling, link regeneration, invitation acceptance, editor removal, last-admin protection, validation, sign-in throttling, and anonymous read-only behavior. A second isolated scenario verifies bootstrap-registration rollback and concurrency.

Run only the isolated bootstrap-registration verification with:

```bash
mise run verify-bootstrap-registration
```

Production packaging and deployment verification have separate checks:

```bash
mise run docker-build
java scripts/verify-production-deployment.java self-test
```

The production deployment verifier has deterministic self-tests for argument validation, exact-revision matching, bounded polling, transient network failures, mid-pass deployment changes, direct authentication redirects, secure cookie attributes, and exact response-header contracts. Running the verifier against a service is read-only.

Java application builds and commands that mutate Docker or Compose state use one workspace operation lock. A second conflicting invocation fails immediately instead of racing over application build output or containers. Long-running `mise run dev` remains unlocked.

Pull requests run Java static analysis, CSS linting, the Maven build and reproducibility check, the full PostgreSQL-backed Chromium suite, focused Firefox and WebKit smoke journeys, bootstrap-concurrency verification, Lighthouse performance budgets against the exact production image, the production smoke test, image SBOM generation and vulnerability scanning, Dependency Review, and CodeQL across Java, repository JavaScript, and GitHub Actions workflow logic. Lighthouse reports are retained as workflow artifacts for 14 days. A separate daily and manually dispatchable workflow rebuilds the current default-branch production image and regenerates its SBOM. Its JSON report retains moderate, high, and critical findings for review, while the automated gate blocks on high or critical vulnerabilities.

The live `master protection` repository ruleset does not currently require CI status checks. In GitHub, open **Settings**, **Rules**, **Rulesets**, edit the active `master protection` ruleset, enable **Require status checks to pass**, and add the GitHub Actions checks `Required PR checks`, `Code analysis (java-kotlin)`, `Code analysis (javascript-typescript)`, and `Code analysis (actions)`. Do not describe `master` as CI-gated until all four are required. Also enable GitHub's native automatic Maven dependency submission. Dependency updates are intentionally initiated and reviewed manually; do not enable automated dependency-update bots.

## Running with Liberty dev mode

Check the toolchain, start PostgreSQL, and start dev mode:

```bash
mise run setup
mise run db
mise run dev
```

The development command cleans only its dedicated `.build/development` output before startup, prepares the Maven-managed PostgreSQL driver, and keeps the generated Liberty installation under `.liberty/`. Clean distributable builds use `.build/package`, so packaging does not remove or modify a running development server. The direct development listener is forced to `127.0.0.1`; the production container explicitly overrides that host so Railway and the container network can reach it. No generated runtime or downloaded driver is committed.

Dev mode requires `t` before running tests on demand and excludes integration and browser tests so an interactive development server cannot write end-to-end fixtures into the normal development database. Use `mise run package` for the normal test suite and the dedicated end-to-end tasks for isolated browser verification.

To verify an already-running local application without rebuilding or starting services:

```bash
mise run verify-local
```

This check always uses the loopback HTTP health endpoint derived from `PORT`; it does not use the browser-facing HTTPS `APP_BASE_URL`. For dev mode it verifies that Liberty's loose application points to this repository, every current Java source and resource has a non-stale development output, no deleted source left an orphaned class or resource, and the active Liberty configuration matches the source configuration. For the Compose application it verifies the expected deployment revision when CI supplies one, or otherwise requires the current packaged WAR, Liberty configuration, image tag, and recorded build-input identity. It always verifies the running web and PostgreSQL Compose configuration hashes and exact loopback bindings, including when CI supplies an expected revision and when the application itself runs in direct dev mode. Both paths require the current Flyway schema and a live application JDBC connection with the port-specific application name on the intended Compose database; a healthy stale process or an application connected to a different database does not pass.

## Building Docker image

Build the production image:

```bash
mise run docker-build
```

The multi-stage build compiles production source without test compilation and produces an Open Liberty Java 25 runtime image. Run `mise run package` as the test gate; CI does so independently before accepting the production image. The build records the resulting image identifier together with a content digest of the Dockerfile, Compose build definition, Maven wrapper and project inputs, production source, and deployment-revision build argument. Inputs are hashed before and after Docker runs, so a concurrent source change fails the build instead of certifying a stale image. Maven, source files, local environment files, and credentials are not present in the runtime image. Liberty writes JSON logs to standard output and standard error.

Start the production image with Docker Compose and local PostgreSQL:

```bash
mise run docker-up
docker compose --profile application logs --follow web
```

The Compose application profile uses the local database service and exposes PostgreSQL, the HTTP health port, and the HTTPS browser port only on the host's loopback interface. Session cookies are always Secure, including during local development, so use the documented HTTPS URL for browser workflows. If port `9443` is unavailable, set `HTTPS_PORT` and `APP_BASE_URL` together; for example, use `HTTPS_PORT=9446` and `APP_BASE_URL=https://localhost:9446` in `.env`. Set `PORT` separately if the HTTP health port is unavailable.

Confirm the runtime directly:

```bash
curl --fail http://localhost:9080/health
```

## Deploying to Railway

Use one Railway project with a PostgreSQL service named `Postgres` and a web service named `shared-calendar-web`. The committed `railway.json` owns the repeatable web build and deployment settings: the root Dockerfile, source watch paths, EU West placement, the single web replica, `/health` deployment gate, bounded restart policy, and graceful draining. Railway resource creation, database references, secrets, volumes, and domains remain environment state and are managed through Railway's API, MCP integration, CLI, or dashboard.

The committed Railway healthcheck timeout is two hours. This intentionally exceeds Railway's five-minute default. On the normal path, migrations 16 and 17 each allow up to five minutes for their expected-fast `NOT VALID` catalog change, migrations 18 and 19 each allow up to five minutes for constraint validation, migrations 20 through 25 each allow up to five minutes for a sequential concurrent index build, and migrations 26 through 28 each allow up to five minutes for a sequential concurrent index drop. Migrations 18 through 28 therefore account for about 55 minutes of configured statement ceilings, and the full normal migration 16 through 28 sequence accounts for about 65 minutes before startup and lock overhead. Migrations 20 through 25 also issue a timeout-bounded defensive drop of their own target name; those targets are absent on the normal path, but removing indexes left by interrupted builds can add up to another 30 minutes during recovery. Migration 14's earlier canonical-token constraint validation has no statement timeout. The two-hour deployment gate does not extend any migration's own timeout. If startup legitimately needs this budget, keep the previous deployment active and follow the write-maintenance procedure above where required; do not repeatedly restart the migration sequence. Railway documents the configurable timeout in its [healthcheck reference](https://docs.railway.com/deployments/healthchecks).

1. Provision a PostgreSQL 17 service named `Postgres`, attach a persistent volume at `/var/lib/postgresql/data`, and create `shared-calendar-web` in the same project and environment.
2. Deploy this repository root. Railway detects `railway.json` and the root `Dockerfile`.
3. Keep the `numReplicas` value in `railway.json` at one. Authenticated cookies and inactivity timeouts roll for 30 days, anonymous sessions expire after 10 minutes of inactivity, and all underlying HTTP sessions remain in memory.
4. Let Railway inject `PORT`; the container binds it on all interfaces.
5. Add the PostgreSQL references and application variables below.
6. Generate a Railway service domain and verify `/health` before configuring DNS.
7. Add `calendar.social` to the web service. In Namecheap Advanced DNS, create the ownership-verification `TXT` record exactly as Railway reports it and an `ALIAS` record with host `@` pointing to Railway's domain target. Remove conflicting `A`, `AAAA`, `CNAME`, `ALIAS`, or redirect records for `@` first.
8. Wait for Railway to report the domain and certificate as active, then perform the production checks below.

Connect the production web service to `Tenemo/event-calendar` with `master` as its deployment branch. Railway automatically deploys every new commit pushed or merged to `master`. The active GitHub ruleset requires pull requests but does not gate merges on Actions until the four status checks documented above are added. Railway's optional **Wait for CI** setting can additionally delay each post-merge deployment until the workflows triggered by that `master` push finish.

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
APP_LTPA_KEYS_PASSWORD=<generated high-entropy secret of at least 32 characters>
APP_BOOTSTRAP_INVITE_TOKEN=
APP_DESCRIPTION_MIGRATION_MAINTENANCE_ACKNOWLEDGED=
```

Set `APP_BOOTSTRAP_INVITE_TOKEN` temporarily to a generated unpadded Base64URL token representing at least 32 random bytes for the first registration only. After the first account is created, delete or clear the variable and redeploy; the database also records permanent bootstrap consumption.

[Railway provides `RAILWAY_GIT_COMMIT_SHA` to GitHub-triggered builds](https://docs.railway.com/variables/reference), and [Dockerfile variables are available as build arguments](https://docs.railway.com/builds/dockerfiles). Do not configure that platform-owned variable manually. The Dockerfile accepts only one complete Git commit SHA, normalizes it, and embeds it in the WAR. `/health` reads only that artifact-bound value and returns it in the no-store `X-Deployment-Revision` response header. A stale image therefore identifies the commit it actually contains rather than whichever revision happens to be running. A missing value omits the resource and header, a malformed nonempty build argument fails the image build, and the existing database-aware `200 ok` or `503 unavailable` health contract remains unchanged.

After each deployment, require production to serve the exact expected commit and the read-only smoke contracts:

```bash
java scripts/verify-production-deployment.java --base-url https://calendar.social --expected-revision <40-character-git-sha> --timeout-seconds 600 --poll-interval-seconds 5
```

GitHub runs the same verifier for a successful Railway `production` deployment status, using the exact SHA recorded by that deployment event. This genuinely post-deploy trigger cannot form a cycle with Railway's optional **Wait for CI** gate. Manual workflow dispatch supports deliberate verification of another full SHA and HTTPS origin.

The command accepts HTTPS origins without credentials, paths, query parameters, or fragments; plain HTTP is limited to literal loopback hosts for local verification. It waits only within the configured deadline for `/health` to prove both database availability and the exact deployment revision. It then verifies stable home and sign-in page markers, secure emitted cookies and exact session-cookie attributes without retaining cookie values, the exact origin-relative `/login` redirect for an anonymous protected request, the committed response-security headers and dynamic no-store policies, and the rejected legacy calendar route. Resolving that relative redirect at the required production HTTPS origin keeps authentication on HTTPS without trusting a proxy-supplied host or scheme. A final health and revision check prevents a deployment change during the smoke pass from being accepted. The verifier does not follow redirects, store cookies, mutate production data, or print response bodies, cookie values, redirect targets, or unvalidated option values.

### PR preview environments

Railway PR environments clone the configured `preview-base` environment. Keep focused PR environments and bot PR environments disabled unless their broader deployment scope is intentional. The committed `railway.json` applies to every clone; enabling and selecting the base environment remain Railway project settings rather than repository settings.

Every non-production Railway environment emits `X-Robots-Tag: noindex, nofollow` on every application response. The preview verification workflow runs after Railway reports a successful `event-calendar-pr-<number>` deployment. It resolves the public service URL only from the authenticated Railway GitHub App's bot comment, verifies the exact deployed commit with the read-only production verifier, then registers or signs in as `preview-pr-<number>` and proves that the protected calendar page and Secure, HTTP-only, SameSite `Lax` cookies work through Railway's proxy. The default-branch checkout protects the verifier from pull-request changes, but the PR-built preview application still receives the credentials through normal registration and sign-in requests. This is an explicitly accepted non-production risk: the credentials are intentionally reusable, low-sensitivity, and preview-only. They must never be production credentials, protect production data, or grant access outside disposable preview environments.

Create these GitHub Actions repository secrets before requiring the preview check:

```text
PREVIEW_BOOTSTRAP_INVITE_TOKEN=<the APP_BOOTSTRAP_INVITE_TOKEN value inherited from preview-base>
PREVIEW_VERIFICATION_PASSWORD=<a saved high-entropy password that follows the application password policy>
```

Each cloned database can consume the inherited bootstrap token independently. The stable per-PR account survives ordinary redeployments because its database belongs to the PR environment. If someone consumed bootstrap registration in an existing preview before the verifier created its predictable account, recreate that PR environment once.

For a quick sign-in reminder, use the authenticated GitHub CLI to resolve the current Railway URL and print the predictable username:

```bash
node scripts/preview-login.mjs 19
```

Use the saved `PREVIEW_VERIFICATION_PASSWORD`; the helper never reads or prints it. Closing the pull request lets Railway remove its environment and database.

After the automated check passes, verify registration, sign-in, password change, calendar links, invitations, role changes, and event persistence through the normal manual release checklist. Redeploy, confirm that accounts and calendar data persist, then sign in again because HTTP sessions are intentionally in memory. Inspect logs to confirm that passwords, database credentials, calendar link tokens, and invitation tokens are absent. Railway's deployment health check is not continuous monitoring, so configure an external HTTPS uptime check for `https://calendar.social/health` before relying on the service.

The application rejects malformed calendar paths before database access. Sign-in throttling uses Railway's `X-Real-IP` address only when the deployment and immediate peer match the configured Railway ingress boundary; elsewhere it uses the direct TCP peer. IPv6 clients are grouped by their `/64` network prefix. Missing, malformed, ambiguous, or untrusted client-address headers fall back to the peer-derived source. Railway remains responsible for network-level traffic protection.

## Registration

Registration requires an unused, unrevoked invitation token. On a brand-new empty database, create the first account by temporarily setting `APP_BOOTSTRAP_INVITE_TOKEN` to a 43-80 character unpadded Base64URL token generated from at least 32 cryptographically random bytes and opening:

```text
/register?token=the-random-value
```

After the first account is created, clear `APP_BOOTSTRAP_INVITE_TOKEN` and restart or redeploy the application. Bootstrap admission is claimed in the same database transaction as registration: a failed registration rolls the claim back, while the first successful registration consumes it atomically and permanently. Concurrent attempts cannot create more than one first account, and deactivating every account does not enable bootstrap again. The normal registration path uses single-use links created from `/app/invitations`.

Passwords must be between 15 and 512 Unicode code points, be nonblank after NFKC normalization and surrounding-space removal, differ from the username under the same case-insensitive normalization, and be entered identically in the password and confirmation fields. Browser fields allow up to 1,024 UTF-16 units so 512 supplementary Unicode code points can reach the authoritative server policy. Uppercase letters, digits, and other composition categories are not required. The submitted password itself is stored only as a PBKDF2-HMAC-SHA256 hash with 600,000 iterations, a 32-byte salt, and a 32-byte derived key; plaintext passwords are never stored.

Five failed sign-in attempts for one normalized username from one client source within 15 minutes block that username/source pair until the window expires. Twenty-five attempts from one source in the same window block further attempts from that source, which limits username spraying without letting one remote client lock the account for other sources. Missing and existing usernames follow the same policy and return the same generic failure. Tracking is bounded to 1,000 username/source pairs and 1,000 sources; the oldest entry is discarded when either map is full.

Liberty and the application return `413` for declared HTTP request bodies larger than 1 MiB. Body-capable requests without a declared `Content-Length`, including chunked requests, fail before parsing with `411`; this browser-form application does not accept streaming request bodies. Automatic request decompression is disabled, and the application returns `415` for every request `Content-Encoding` other than `identity` before servlet code reads or parses the body.

Anonymous Faces views, including sign-in, receive browser-session cookies and expire on the server after 10 minutes of inactivity. An anonymous canonical calendar invalidates its temporary server-side Faces session after rendering when no pagination postback remains. A calendar with more events retains that state only while its **Load more events** control still needs it; loading the final page invalidates the state, while an abandoned paginated view expires after 10 minutes. This keeps anonymous pagination functional without retaining server-side view state for ordinary public reads.

Only successful authentication extends a server session to 30 days and allows the application to issue an unconditionally Secure, HTTP-only, SameSite `Lax` persistent cookie. Liberty also restricts its HTTP-only, SameSite `Lax` authentication cookie to secure requests and rejects SSO tokens after sign-out on the running instance. Authenticated application and calendar requests refresh both the cookie and the 30-day inactivity window. A server restart or redeploy clears in-memory sessions and requires reauthentication; submitting a sign-in form that was open before the restart returns the browser to a fresh sign-in form. The stable `APP_LTPA_KEYS_PASSWORD` lets Liberty reopen its generated signing-key file, while accounts and calendar data remain in PostgreSQL.

## Password changes

Open `/app/account-settings` while signed in. Enter the current password, then enter and confirm a different new password that follows the registration password policy. A successful change writes a new salted password hash, records a secret-free audit entry, signs out the current browser, and requires the new password at the next sign-in.

Each authenticated session records the account's database password version. Changing the password increments that version, so every other browser session is rejected before its next protected application request and must sign in with the new password. A stale session opening a canonical calendar URL is discarded and the same URL is re-evaluated with ordinary anonymous read-only permissions.

This flow requires a valid signed-in session and the current password. Forgotten-password recovery is not implemented because the application does not yet have a verified email or other recovery channel.

## Security audit records

Successful, failed, and throttled sign-ins, explicit sign-outs, and forced stale-session invalidations emit structured authentication audit events without usernames, client input, passwords, or bearer tokens. Each event type emits at most ten direct records per minute, followed by one suppression notice and a count when the next window begins. This fixed-memory rate limit prevents an attacker from creating unbounded database rows or log volume. These events use the application server log rather than `audit_log`; configure Railway log export and retention if they must survive the platform's available log window.

Authenticated mutations are recorded in PostgreSQL's `audit_log`. The application does not run a background retention job; if these records ever need pruning, handle it as ordinary database maintenance.

## Calendar roles

- `EDITOR` can view and create, edit, or delete events.
- `ADMIN` has editor permissions and can change calendar settings and manage members.

Role checks are enforced by services, not only by hidden UI controls. Every active calendar must retain at least one active admin. An admin cannot demote or remove their own membership; another admin must perform that change.

Removing an editor disables their membership and removes the calendar from their account. It does not revoke the bearer link: if public access remains enabled and the former editor retained the current URL, they can still read the calendar with exactly the same permissions as any anonymous visitor. Disable public access or regenerate the calendar link when everyone using the shared URL must lose access.

## Event times

Timed events are entered in the calendar's IANA time zone and stored with their actual UTC offsets. Nonexistent or ambiguous local times at daylight-saving transitions are rejected instead of being guessed.

For all-day events, the first and last dates shown in the form are both inclusive. The service receives those civil dates directly and persistence uses a start-inclusive, end-exclusive range from the first day's calendar-local start to the calendar-local start of the day after the last date. This preserves the intended civil dates across short, long, skipped, and repeated days. Changing a calendar's time zone renormalizes existing all-day boundaries without changing the displayed dates. To keep that settings transaction bounded, a time-zone change supports at most 1,000 all-day events and is rejected when the calendar has more. An event form opened before any concurrent calendar-settings change is rejected with a reload message instead of interpreting its values in a different time zone.

## Calendar links and public access

New calendars have public access enabled and receive a bearer token made from 64 cryptographically random bits, encoded as exactly 11 unpadded Base64URL characters. `/{calendarLinkToken}` is the one canonical calendar URL: it is the address editors and admins see in their browser, and it is the address they copy and share. There is no `/calendar/` prefix. Active editors and admins see mutation controls at that URL. Anyone else with the URL receives only the read-only calendar, without signing in, so the URL should be treated as a secret. Canonical bearer routes and every response whose query contains a decoded `token` or `invite` parameter emit `Referrer-Policy: no-referrer`, including after response resets, so same-origin navigation and resource requests do not disclose the capability URL. Query-bearing capability responses also emit an enforced `X-Robots-Tag: noindex, nofollow`, including in production and for encoded or matrix-path aliases, so downstream code cannot accidentally make a live invitation indexable.

Only exact one-segment paths containing the canonical unpadded Base64URL encoding of eight bytes enter calendar lookup. That means ten URL-safe Base64 characters followed by one of `AEIMQUYcgkosw048`; other 11-character lookalikes are rejected without a calendar database query. Missing, disabled, and regenerated tokens use the same link-unavailable `404` without echoing the candidate token.

An admin can disable public access without changing the URL. Active editors and admins can continue using that same URL, while everyone else receives a `404` page explaining that the link may have been regenerated or public access may be disabled. Re-enabling public access restores read-only access at the same URL.

Any active editor or admin can use **Regenerate link** on the calendar page. Regeneration creates a new canonical URL and immediately invalidates the previous URL for everyone. Members can reach the new URL from **My calendars**; anonymous readers need to receive the new link. Regeneration does not change memberships or grant mutation access through the link.

## Invitations

Signed-in users can create registration invitations. Calendar editors and admins can create editor invitations that grant `EDITOR` membership on a selected calendar. There is no read-only membership invitation; share the calendar URL for read-only access. One account may have at most 20 outstanding registration invitations and may create at most 100 invitations of any type in a rolling 24-hour window. One calendar may have at most 50 unaccepted, unrevoked, unexpired editor invitations, including links that are temporarily unusable because their creator is inactive or no longer has calendar access, and may receive at most 100 editor invitations in a rolling 24-hour window. Calendar invitation creation locks the calendar before the creating account, while registration invitation creation locks only the creating account; these locks serialize the applicable limits without reversing the calendar-membership mutation lock order.

Invitation links are single-use bearer secrets and expire exactly seven days after creation; callers cannot request a longer lifetime, and the database rejects one. Opening a link checks its timestamp, scope, creator status, calendar status, and current creator permission without consuming it. A usable page shows the safe invitation scope, calendar name when applicable, and exact expiration before any action; every other state shows the same generic unavailable page without a registration or acceptance form. Their creator can revoke them while unused; a calendar admin can also list and revoke unused editor invitations for that calendar. Every invitation stops working if its creator's account becomes inactive, and an editor invitation also stops working if its creator loses permission to edit that calendar. The invitation history uses a stable identifier snapshot and immutable creation order while the view is open, and its availability label applies the same creator, calendar, scope, and permission rules as acceptance. Acceptance revalidates those permissions and serializes concurrent claims, so one invitation can be consumed by exactly one account. A new user registers through either invitation type; an existing user can explicitly accept only an editor invitation. Tokens are not written to application logs.

## Backup and restore

Production data protection uses Railway PostgreSQL snapshots. Confirm that snapshots are enabled and recent in Railway before a migration or other risky database operation. The repository does not contain custom backup, restore, scheduling, or retention tooling.

Restore snapshots through Railway into a non-production environment first when practical, verify `/health` and representative calendar access, and only then follow Railway's documented production recovery procedure. Never test a restore by replacing the live production database.

## Troubleshooting

### PrimeFaces class errors

The PrimeFaces dependency probably lacks the `jakarta` classifier. Check it with:

```bash
./mvnw dependency:tree -Dincludes=org.primefaces:primefaces
```

### Railway 502 or application failed to respond

Confirm that the production container supplies `HTTP_HOST=*`, the web service receives Railway's `PORT`, the domain targets that port, and `/health` succeeds in service logs. The Liberty configuration defaults `HTTP_HOST` to loopback for direct local development, so a production runtime that bypasses the committed container must set the container-network binding explicitly.

### Sign-in works locally but not in production

Confirm that `APP_BASE_URL` exactly matches the HTTPS domain, `APP_LTPA_KEYS_PASSWORD` is present and unchanged, only one application replica is running, and the browser is not switching between generated and custom domains. Session cookies are always Secure and cannot be downgraded through configuration. A `CWWKS4106E`, `CWWKS4118E`, or `CWWKS4000E` message after a restart indicates that Liberty could not reopen its authentication signing keys or start the token service; restore the same production LTPA password instead of generating a replacement. If a disposable local `.liberty` runtime predates the stable local password setting, stop that local server, delete only `.liberty/user/servers/defaultServer/resources/security/ltpa.keys`, and restart once so Liberty regenerates the file with the current local value. Never use that local-only recovery for a production key file.

### Calendar link does not work

For an anonymous reader, confirm that public access is enabled and the URL has not been regenerated. The path must be exactly one root segment containing 11 Base64URL characters, with no `/calendar/` prefix. Editors and admins can open the current URL from **My calendars** even while public access is disabled. Also confirm that `APP_BASE_URL` is correct for generated invitation links.

### Tables are missing

Check application startup logs for Flyway errors, verify all PostgreSQL variables, and confirm that the PostgreSQL driver exists in Liberty's shared resources. The application logs the applied Flyway version after successful startup.

### Production container does not start locally

Check `docker compose --profile application logs web postgres`, confirm that ports `9080` and `9443` are free or update `PORT`, `HTTPS_PORT`, and `APP_BASE_URL` as described above, and verify that Docker Compose reports PostgreSQL as healthy.

## Known limitations

- Railway's deployment health check is not continuous monitoring; external uptime monitoring and alerting are not configured by this repository.
- Database snapshots and retention are Railway environment settings that the repository cannot provision, monitor, or guarantee.
- Run one application instance because HTTP sessions are in memory, even though active authenticated cookies and inactivity timeouts roll for 30 days.
- Forgotten-password account recovery is not implemented; signed-in password changes are available from account settings.
- Recurring events, notifications, email delivery, ICS import/export, and native mobile apps are intentionally out of scope.
