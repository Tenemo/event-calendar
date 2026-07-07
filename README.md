# Shared calendar

Shared calendar is a personal shared-calendar web app built as one server-rendered Jakarta EE application.

## Product model

The app is intended for shared event calendars: kayaking plans, birthdays, trips, and similar friend-group coordination.

- Registered users can create their own calendars.
- Calendar creators become admins of the calendars they create.
- Calendar admins can invite editors and viewers.
- Calendars are public by default through long, unguessable links.
- Public calendar links are read-only and should not be crawlable.
- No mobile app, recurrence, ICS import/export, reminders, or notifications are planned for v1.

## Stack

- Java 25 runtime with Java 21 source compatibility
- Maven WAR build
- Open Liberty with Jakarta EE 10 Web Profile
- Jakarta Faces / JSF and PrimeFaces with the `jakarta` classifier
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

## Environment variables

Copy `.env.example` to `.env` for local values. The foundation milestone uses defaults from the Liberty configuration, but the variables are already named for the later database, authentication, and deployment work.

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
APP_REGISTRATION_ENABLED=true
```

## Database

Local PostgreSQL runs through Docker Compose with the `postgres:17` image. Host-installed `psql`, `pg_dump`, and `pg_restore` are not required.

Inspect the local database through the container:

```bash
docker compose exec postgres psql -U calendar -d calendar -c 'select current_database(), current_user;'
```

## Running tests

Run the Maven build through the project task:

```bash
mise run package
```

## Running with Liberty dev mode

Prepare the local Liberty resources, start PostgreSQL, and start Liberty:

```bash
mise run setup
mise run db
mise run dev
```

The setup task copies the PostgreSQL JDBC driver into Liberty's local config resources. The copied jar is ignored by source control.

The project uses `mise` tasks and a portable Java helper for local orchestration instead of maintaining separate Bash and PowerShell scripts. Java and Maven versions are pinned in `.mise.toml`; the PostgreSQL driver version is centralized in `pom.xml`.

## Known limitations

This foundation only provides the runnable application shell, Open Liberty configuration, Dockerized PostgreSQL, placeholder Jakarta Faces pages, PrimeFaces rendering, and `/health`. Registration, login, persistence migrations, calendar creation, public token routing, invitations, calendar event storage, production Docker packaging, deployment, and backup/restore are added later.
