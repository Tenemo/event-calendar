# M3: production readiness

Use this milestone to make the app packageable, deployable, recoverable, documented, and safe enough for real personal use.

## Milestone checklist

Outcome: the app is packaged, deployable to Railway, recoverable from backup, and documented for real use with registration, public calendar links, invite links, calendar roles, and event workflows.

Tasks:

1. Build the production Docker image.
2. Run the app container locally against Dockerized PostgreSQL.
3. Confirm health, registration, login, calendar creation, public links, invite links, event CRUD, member management, environment variables, and `COOKIE_SECURE=false` locally.
4. Add Dockerized backup and restore scripts.
5. Test backup and restore against a fresh local Docker Compose database.
6. Create the Railway project, PostgreSQL service, and web service.
7. Configure Railway variables and deploy.
8. Confirm generated Railway domain, then custom domain and HTTPS.
9. Register a real account, create a calendar, create a public link, create an invite link, accept the invite with a second account, and verify events persist across redeploy.
10. Update README with local setup, deployment, environment variables, roles, registration, public links, invitations, backup/restore, troubleshooting, and known limitations.
11. Extend GitHub PR checks with Docker build coverage and available security checks.

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
8. Registration works in production.
9. Login works after redeploy.
10. Calendar public links work after redeploy.
11. Invite links work after redeploy.
12. Events persist after redeploy.
13. Railway logs do not contain passwords, public tokens, invite tokens, or database credentials.
14. README has local setup, deploy setup, backup/restore, registration, roles, public links, invitations, troubleshooting, and known limitations.
15. PR checks include the Maven build, database-backed tests, app smoke where deterministic, Docker image build, and enabled security checks.

## Docker build

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
  -e APP_REGISTRATION_ENABLED=true \
  shared-calendar:local
```

On Linux, `host.docker.internal` may need extra configuration. If it fails, run the app and PostgreSQL in one Docker network or use the host IP.

## GitHub PR checks after M3

M3 should make production packaging visible in PRs.

Required PR checks should include:

1. Maven wrapper build: `./mvnw -B -ntp clean test package`.
2. PostgreSQL-backed migration and service tests.
3. App smoke against a running Liberty app when M2 route checks are deterministic.
4. Docker image build: `docker build -t shared-calendar:ci .`.

Add a container smoke check after the Docker image can reliably connect to the CI PostgreSQL service. The smoke should confirm `/health` and one or two stable application routes, not perform deployment.

Security checks:

1. Add GitHub Dependency Review on pull requests if the repository has Dependency Review available. Configure it to fail on new vulnerable dependencies, not on unrelated existing alerts.
2. Add CodeQL Java analysis on pull requests, pushes to `master`, and a weekly schedule if code scanning is available for the repository.
3. Add Dependabot configuration for Maven dependencies and GitHub Actions updates after the initial workflow is stable.

Do not deploy to Railway from pull requests. Production deploys should remain manual or protected on `master` until the owner explicitly asks for automated deployment. PR workflows must not require Railway secrets.

## Railway deployment plan

Create one Railway project with two services:

```text
shared-calendar-web
postgres
```

The web service uses the repository root `Dockerfile`. PostgreSQL is a Railway PostgreSQL service.

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
APP_REGISTRATION_ENABLED=true
```

Adjust the `${{Postgres.*}}` namespace to match the actual Railway PostgreSQL service name.

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

Backup and restore must not depend on host-installed PostgreSQL client tools. Use Dockerized clients:

1. For the local Docker Compose database, run `pg_dump` and `pg_restore` inside the `postgres` service container.
2. For remote databases, run a temporary `postgres:17` client container and connect with `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD`.

Acceptance criteria for backups:

1. Agent can run backup against the local Docker Compose database.
2. Agent can restore into a fresh local Docker Compose database.
3. README documents production backup process.
4. README states that host PostgreSQL client utilities are not required.
5. Do not claim production readiness until a restore has been tested.

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
