# Shared calendar

Shared calendar is a personal shared-calendar web app built as one server-rendered Jakarta EE application.

## Product model

The app is intended for shared event calendars: kayaking plans, birthdays, trips, and similar friend-group coordination.

- Registered users can create their own calendars.
- Calendar creators become admins of the calendars they create.
- Signed-in users can invite new users to the app.
- Calendar editors and admins can invite new users as editors for calendars they can edit.
- Account registration is invitation-only through app invitation links.
- Calendars are public by default through long, unguessable links.
- Public calendar links are read-only, require no sign-in, and should not be crawlable.
- No mobile app, recurrence, ICS import/export, reminders, or notifications are planned for v1.

## Stack

- Java 25 runtime with Java 21 source compatibility
- Maven WAR build
- Open Liberty with Jakarta EE 10 Web Profile
- Jakarta Faces / JSF and PrimeFaces with the `jakarta` classifier
- Jakarta Security, CDI / EJB Lite services, and JPA
- Flyway database migrations
- Docker Compose PostgreSQL 17 for local development

## Local development

Install `mise` and Docker, then run:

```bash
mise trust
mise run setup
mise run db
mise run dev
```

The app listens on `http://localhost:9080` by default. The liveness check is available at `http://localhost:9080/health`.

Jakarta Faces extensionless routing is enabled, so browser-facing pages use clean paths such as `/login`, `/register`, `/public-calendar`, `/app/calendars`, `/app/calendar-members`, and `/app/invitations`. The underlying Facelets files remain `.xhtml` files.

## Environment variables

Copy `.env.example` to `.env` for local values. Liberty and Docker Compose use these values for the HTTP port, PostgreSQL connection, cookie mode, and browser-test base URL.

Current local variables:

```bash
PORT=9080
COOKIE_SECURE=false
PGHOST=localhost
PGPORT=5432
PGDATABASE=calendar
PGUSER=calendar
PGPASSWORD=calendar
APP_TIMEZONE=Europe/Warsaw
APP_BASE_URL=http://localhost:9080
APP_BOOTSTRAP_INVITE_TOKEN=
```

`APP_BOOTSTRAP_INVITE_TOKEN` is optional and should be blank during normal use. On a brand-new empty database, set it to a long random secret, open `/register?token=that-secret`, create the first account, then remove the value and restart the app. After that first account exists, app invitation links are generated from `/app/invitations`.

## Database

Local PostgreSQL runs through Docker Compose with the `postgres:17` image. Flyway migrations run during application startup and own the database schema. Host-installed `psql`, `pg_dump`, and `pg_restore` are not required.

Inspect the local database through the container:

```bash
docker compose exec postgres psql -U calendar -d calendar -c 'select current_database(), current_user;'
docker compose exec postgres psql -U calendar -d calendar -c '\dt'
docker compose exec postgres psql -U calendar -d calendar -c 'select installed_rank, version, description, success from flyway_schema_history order by installed_rank;'
```

## Authentication and calendars

Users can register only through a valid single-use app invitation link. Registration creates the user, hashes the password through Jakarta Security's built-in `Pbkdf2PasswordHash`, creates the first calendar, enables its public-link flag, generates a random bearer token for that public link, and grants the creator calendar-scoped `ADMIN` membership. If the invitation is scoped to a calendar, registration also grants `EDITOR` membership on that calendar.

Passwords must be at least 14 characters, nonblank, and different from the username. Password hashes use Jakarta Security's PBKDF2-HMAC-SHA256 format with 600,000 iterations, a 32-byte salt, and a 32-byte derived key. Plaintext passwords are never stored.

The application uses `USER` for signed-in pages. There is no global administrator role. Calendar permissions are loaded from `calendar_member` and enforced in services with calendar-scoped `VIEWER`, `EDITOR`, and `ADMIN` roles.

Signed-in users can create single-use app-only registration links at `/app/invitations`. Calendar editors and admins can also create app invitation links that grant `EDITOR` membership on one of their editable calendars. Invitation tokens are bearer secrets and should be shared only with the intended person.

## Running tests

Run the Maven build through the project task:

```bash
mise run package
```

Browser end-to-end tests use Playwright Java and expect the app to be running. Start the local services first:

```bash
mise run setup
mise run db
mise run dev
```

In another terminal, install the Playwright browser binaries and run the browser tests:

```bash
mise run install-playwright
mise run e2e
```

The browser tests use Chromium by default. Set `BROWSER` to `firefox` or `webkit` to use another Playwright browser, and set `APP_BASE_URL` to target a non-default running app URL.

The browser suite covers extensionless routes, noindex public calendar pages, app-only invitation generation, editor invitation generation, invitation-only registration, logout, failed login, successful login, and signed-in calendar creation.

## Running with Liberty dev mode

Prepare the local Liberty resources, start PostgreSQL, and start Liberty:

```bash
mise run setup
mise run db
mise run dev
```

The setup task copies the PostgreSQL JDBC driver into Liberty's generated shared resources under `target/`.

The project uses `mise` tasks and a portable Java helper for local orchestration instead of maintaining separate Bash and PowerShell scripts. Java and Maven versions are pinned in `.mise.toml`; the PostgreSQL driver version is centralized in `pom.xml`.

If Liberty is already running, verify the running app and database without rebuilding:

```bash
mise run verify-running-app
```

## Known limitations

The current UI intentionally exposes only the M1 account and calendar foundation: invitation-only registration, app invitation generation, login, logout, the signed-in calendar list, and basic calendar creation. The service layer and schema include memberships, public tokens, events, audit logs, and role checks, but full event management, member administration, and token-based public calendar routing are still future UI work.

Production Docker packaging, Railway deployment, backup/restore workflows, and operational hardening are not complete yet.
