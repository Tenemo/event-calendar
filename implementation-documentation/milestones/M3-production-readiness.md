# M3: production readiness

Use this milestone to make the app packageable, deployable, recoverable, and documented for real use.

## Milestone checklist


Outcome: the app is packaged, deployable to Railway, recoverable from backup, and documented for real use.

Tasks:

1. Build the production Docker image.
2. Run the app container locally against Dockerized PostgreSQL.
3. Confirm health, login, calendar, admin, environment variables, and `COOKIE_SECURE=false` locally.
4. Add Dockerized backup and restore scripts.
5. Test backup and restore against a fresh local Docker Compose database.
6. Create the Railway project, PostgreSQL service, and web service.
7. Configure Railway variables and deploy.
8. Confirm generated Railway domain, then custom domain and HTTPS.
9. Log in as bootstrap admin, change the password, remove the bootstrap password variable, and redeploy.
10. Update README with local setup, deployment, environment variables, roles, backup/restore, troubleshooting, and known limitations.

Verification:

```bash
./mvnw clean test package
docker compose up -d postgres
docker build -t shared-calendar:local .
docker run --rm -p 9080:9080 ... shared-calendar:local
curl -i http://localhost:9080/health
scripts/backup-postgres.sh
scripts/restore-postgres.sh path/to/backup.dump
```

Acceptance criteria:

1. Docker image builds from a clean checkout.
2. Runtime does not need Maven.
3. App logs to stdout/stderr.
4. No secrets are baked into the image.
5. Dockerized backup and restore work without host PostgreSQL client utilities.
6. `https://<railway-domain>/health` returns `ok`.
7. Custom domain works over HTTPS.
8. Login works after redeploy.
9. Events persist after redeploy.
10. Railway logs do not contain passwords.
11. README has local setup, deploy setup, backup/restore, admin bootstrap notes, troubleshooting, and known limitations.

---



## Implementation details

## 16. Docker build

Create `.dockerignore`:

```text
.git
.idea
.vscode
target
.env
*.log
```

Create `Dockerfile`:

```dockerfile
FROM eclipse-temurin:25-jdk AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY src ./src
RUN ./mvnw -DskipTests package \
    dependency:copy \
    -Dartifact=org.postgresql:postgresql:42.7.13 \
    -DoutputDirectory=target/driver

FROM icr.io/appcafe/open-liberty:kernel-slim-java25-openj9-ubi-minimal

COPY --chown=1001:0 src/main/liberty/config/server.xml /config/

RUN features.sh

COPY --chown=1001:0 --from=build /app/target/driver/postgresql-*.jar /config/resources/
COPY --chown=1001:0 --from=build /app/target/shared-calendar.war /config/apps/shared-calendar.war

RUN configure.sh
```

Local Docker test:

```bash
docker build -t shared-calendar:local .

docker run --rm \
  -p 9080:9080 \
  -e PORT=9080 \
  -e COOKIE_SECURE=false \
  -e PGHOST=host.docker.internal \
  -e PGPORT=5432 \
  -e PGDATABASE=calendar \
  -e PGUSER=calendar \
  -e PGPASSWORD=calendar \
  -e APP_TIMEZONE=Europe/Warsaw \
  -e APP_BASE_URL=http://localhost:9080 \
  -e APP_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e APP_BOOTSTRAP_ADMIN_PASSWORD=change-me-before-real-use \
  shared-calendar:local
```

On Linux, `host.docker.internal` may need extra configuration. If it fails, run the app and PostgreSQL in one Docker network or use the host IP.

---


## 17. Railway deployment plan

### 17.1 Railway services

Create one Railway project with two services:

```text
shared-calendar-web
postgres
```

`shared-calendar-web` uses the repository root `Dockerfile`.

`postgres` is a Railway PostgreSQL service.

### 17.2 Web service variables

Set these variables on the web service:

```bash
PORT=${{PORT}}
COOKIE_SECURE=true

PGHOST=${{Postgres.PGHOST}}
PGPORT=${{Postgres.PGPORT}}
PGDATABASE=${{Postgres.PGDATABASE}}
PGUSER=${{Postgres.PGUSER}}
PGPASSWORD=${{Postgres.PGPASSWORD}}

APP_TIMEZONE=Europe/Warsaw
APP_BASE_URL=https://calendar.example.com
APP_BOOTSTRAP_ADMIN_USERNAME=<your-admin-username>
APP_BOOTSTRAP_ADMIN_PASSWORD=<temporary-long-random-password>
```

Adjust the `${{Postgres.*}}` namespace to match the actual Railway PostgreSQL service name.

After first successful login:

1. Change the admin password in the app.
2. Remove `APP_BOOTSTRAP_ADMIN_PASSWORD` from Railway variables.
3. Redeploy.
4. Confirm login still works.

### 17.3 Railway public networking

Railway requirements:

1. The container must listen on `0.0.0.0`.
2. The container must listen on the injected `PORT`.
3. The Dockerfile must be named `Dockerfile` with capital `D` at the repo root unless you configure a custom path.

The `server.xml` in this plan satisfies the host and port requirements.

### 17.4 Custom domain

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

---


## 18. Backup and restore

A calendar app is only useful if the database is recoverable.

Backup and restore must not depend on host-installed PostgreSQL client tools. Use Dockerized clients:

1. For the local Docker Compose database, run `pg_dump` and `pg_restore` inside the `postgres` service container.
2. For remote databases, run a temporary `postgres:17` client container and connect with `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD`.

Create `scripts/backup-postgres.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
mkdir -p "$BACKUP_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_DIR/calendar-$STAMP.dump"

if [ "${PGHOST:-localhost}" = "localhost" ] || [ "${PGHOST:-localhost}" = "127.0.0.1" ]; then
  docker compose exec -T postgres pg_dump \
    --format=custom \
    --no-owner \
    --no-acl \
    --username="${PGUSER:-calendar}" \
    --dbname="${PGDATABASE:-calendar}" \
    > "$OUT"
else
  docker run --rm \
    -e PGPASSWORD="$PGPASSWORD" \
    postgres:17 \
    pg_dump \
      --format=custom \
      --no-owner \
      --no-acl \
      --host="$PGHOST" \
      --port="${PGPORT:-5432}" \
      --username="$PGUSER" \
      --dbname="$PGDATABASE" \
    > "$OUT"
fi

echo "Backup written to $OUT"
```

Create `scripts/restore-postgres.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 path/to/backup.dump" >&2
  exit 1
fi

BACKUP_FILE="$1"

if [ "${PGHOST:-localhost}" = "localhost" ] || [ "${PGHOST:-localhost}" = "127.0.0.1" ]; then
  docker compose exec -T postgres pg_restore \
    --clean \
    --if-exists \
    --no-owner \
    --no-acl \
    --username="${PGUSER:-calendar}" \
    --dbname="${PGDATABASE:-calendar}" \
    < "$BACKUP_FILE"
else
  docker run --rm -i \
    -e PGPASSWORD="$PGPASSWORD" \
    postgres:17 \
    pg_restore \
      --clean \
      --if-exists \
      --no-owner \
      --no-acl \
      --host="$PGHOST" \
      --port="${PGPORT:-5432}" \
      --username="$PGUSER" \
      --dbname="$PGDATABASE" \
    < "$BACKUP_FILE"
fi
```

Acceptance criteria for backups:

1. Agent can run backup against the local Docker Compose database.
2. Agent can restore into a fresh local Docker Compose database.
3. README documents production backup process.
4. README states that host PostgreSQL client utilities are not required.
5. Do not claim production readiness until a restore has been tested.

---


## 20. README requirements

Create a README with these sections:

```text
# Shared calendar

## Stack
## Local development
## Environment variables
## Database migrations
## Running tests
## Running with Liberty dev mode
## Building Docker image
## Deploying to Railway
## First admin bootstrap
## Roles
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

### Tables missing

Likely causes:

1. Flyway did not run.
2. Wrong database variables.
3. PostgreSQL driver not copied into Liberty config resources.

---



