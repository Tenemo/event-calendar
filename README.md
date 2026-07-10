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

Jakarta Faces extensionless routing is enabled, so browser-facing pages use clean paths such as `/login`, `/register`, `/app/calendars`, `/app/calendar`, `/app/calendar-members`, `/app/calendar-settings`, and `/app/invitations`. Public calendars use `/calendar/{publicToken}`. The underlying Facelets files remain `.xhtml` files.

## Environment variables

Copy `.env.example` to `.env` for local values. Liberty and Docker Compose use these values for the HTTP port, PostgreSQL connection, cookie mode, and browser-test base URL.

Current local variables:

```bash
PORT=9080
HTTPS_PORT=9443
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

`APP_TIMEZONE` is the default IANA timezone assigned to new calendars. An invalid value prevents the application from starting so a deployment error cannot surface as an end-user calendar-creation failure.

`APP_BASE_URL` is the canonical externally reachable application URL used when generating invitation and public-calendar links. Set it to the HTTPS custom domain in production, without a query string or fragment. When it is unset, request-derived links are allowed only for loopback development hosts such as `localhost`; non-local requests require an explicit canonical URL. `HTTPS_PORT` configures Liberty's local HTTPS listener and can be changed when running an additional local verification server.

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

Invitation links work for both new and existing users. A new user registers through the link; an existing user signs in and explicitly accepts it. Each invitation is single-use and can be revoked by its creator while unused.

## Calendar workflows

The signed-in calendar workspace shows all events in the calendar's configured IANA timezone. `EDITOR` and `ADMIN` members can create, edit, and delete events. Event titles, locations, start and end times, daylight-saving transitions, and stale edits are validated on the server. `VIEWER` members receive the same event view without mutation controls.

Calendar admins can change the name, description, timezone, and public-access state from the settings page. They can copy or rotate the public link; rotation immediately invalidates the old bearer URL. Public pages are read-only, contain `noindex, nofollow`, and return a generic 404 for invalid, disabled, or rotated tokens.

Calendar admins can list active and inactive members, change calendar-scoped roles, reactivate access by saving a role, and remove access. Admins cannot demote themselves or remove their own admin access; another admin must make those changes. The service layer also prevents the last active admin from being demoted or removed. Editors and admins can create editor invitation links, while only admins can manage member roles and calendar settings.

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

The browser suite covers extensionless routes, real 404 responses for invalid and rotated public links, `noindex` public pages, app-only and editor invitation generation, invitation acceptance by new and existing users, registration, login and logout, calendar creation, event create/edit/delete and validation, multi-day all-day event display, calendar identifier persistence across mutations and reloads, settings validation and persistence, accessible public-link viewing, member promotion, demotion, removal and reactivation, protected self-admin controls, missing-identifier handling, and viewer read-only behavior. Focused service tests cover last-admin protection and reject admin self-demotion or self-removal even when another admin exists.

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

Production Docker packaging, Railway deployment, backup/restore workflows, and operational hardening are not complete yet. Recurring events, notifications, email delivery, ICS import/export, and native mobile apps remain intentionally out of scope for v1.
