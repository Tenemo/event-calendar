# Shared calendar

Shared calendar is a personal shared-calendar web app built as one server-rendered Jakarta EE application.

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

## Database

Local PostgreSQL runs through Docker Compose with the `postgres:17` image. Host-installed `psql`, `pg_dump`, and `pg_restore` are not required.

Inspect the local database through the container:

```bash
docker compose exec postgres psql -U calendar -d calendar -c 'select current_database(), current_user;'
```

## Running tests

Run the Maven build through the wrapper:

```bash
./mvnw clean test package
```

On Windows PowerShell, use:

```powershell
.\mvnw.cmd clean test package
```

## Running with Liberty dev mode

Prepare the local Liberty resources, start PostgreSQL, and start Liberty:

```bash
mise run setup
mise run db
mise run dev
```

The setup task copies the PostgreSQL JDBC driver into Liberty's local config resources. The copied jar is ignored by source control.

## Known limitations

This foundation only provides the runnable application shell, Open Liberty configuration, Dockerized PostgreSQL, placeholder Jakarta Faces pages, PrimeFaces rendering, and `/health`. Authentication, authorization, persistence migrations, calendar event storage, user management, production Docker packaging, deployment, and backup/restore are added later.
