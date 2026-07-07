# Project reference

This file contains planning context and standards. Executable implementation work belongs in the files under `milestones/`.

## 1. Non-negotiable decisions

Use these decisions unless the repository owner explicitly changes them later.

| Area                         | Decision                                                                                                             |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| Runtime Java                 | Java 25 LTS                                                                                                          |
| Compiler compatibility level | Java 21 by default; Java 17 only if employer parity requires it; Java 25 only after MVP if portability is not a goal |
| Build tool                   | Maven                                                                                                                |
| Packaging                    | WAR                                                                                                                  |
| Runtime                      | Open Liberty container                                                                                               |
| Enterprise profile           | Jakarta EE 10 Web Profile                                                                                            |
| UI                           | Jakarta Faces 4.0 / JSF + PrimeFaces 15.x `jakarta` classifier                                                       |
| Dependency injection         | CDI                                                                                                                  |
| Service layer                | Stateless Enterprise Beans / EJB Lite where method security is needed                                                |
| Persistence                  | Jakarta Persistence / JPA, provider-neutral code, Liberty default EclipseLink provider                               |
| Database                     | Dockerized PostgreSQL for local development, Railway PostgreSQL for production                                       |
| Migrations                   | Flyway                                                                                                               |
| Auth                         | Username/password, no OAuth/SSO                                                                                      |
| Roles                        | `VIEWER`, `EDITOR`, `ADMIN`                                                                                          |
| Deployment                   | Dockerfile to Railway first                                                                                          |
| Frontend hosting             | None; do not use Netlify for the app UI                                                                              |
| Date/time storage            | UTC-aware PostgreSQL `timestamptz`, Java `OffsetDateTime` or carefully tested `Instant`                              |
| Recurrence                   | Out of scope for v1                                                                                                  |

The most important compatibility rule: **everything must be Jakarta-era code**.

Do not use:

```java
import javax.*;
```

Do use:

```java
import jakarta.*;
```

Do not use old JSF XML namespaces such as:

```xml
xmlns:h="http://java.sun.com/jsf/html"
```

Do use Jakarta Faces namespaces:

```xml
xmlns:h="jakarta.faces.html"
xmlns:f="jakarta.faces.core"
xmlns:p="primefaces"
```

### 1.1 Java 25 policy

Use **Java 25 LTS** for the local developer JDK and for the production Open Liberty container runtime. Java 25 is acceptable for this application because Open Liberty supports Java 25 and publishes Java 25 OpenJ9 container images.

Compile application code with this property unless the owner explicitly changes it:

```xml
<maven.compiler.release>21</maven.compiler.release>
```

Rationale:

1. Running on Java 25 gives the application the current JVM/runtime behavior.
2. Compiling with release 21 keeps source code more conservative and easier to understand for enterprise Java work.
3. Switching `maven.compiler.release` to `17` is valid if employer parity becomes more important.
4. Switching `maven.compiler.release` to `25` is valid only after the MVP is deployed and only if the owner wants to deliberately use Java 25 language/API features.
5. Do not use preview features in this project.

Multiple Java processes can run on the same machine at the same time. The project should not modify global `JAVA_HOME`; it should use repository-scoped tooling.

### 1.2 CLI-first reproducibility policy

The repository must be usable without IntelliJ. IntelliJ may be used by humans later, but all required workflows must be expressible through source-controlled CLI files.

Required pattern:

```text
Git clone
  -> install mise once if missing
  -> create wrapper files if this is the first repository initialization
  -> mise trust
  -> mise run setup
  -> mise run dev
```

Create these source-controlled files early:

```text
.mise.toml
.java-version
.mvn/wrapper/maven-wrapper.properties
mvnw
mvnw.cmd
docker-compose.yml
.env.example
scripts/bootstrap-unix.sh
scripts/check-toolchain.sh
```

Do not put downloaded JDKs, Maven distributions, PostgreSQL driver jars, PostgreSQL data volumes, `.env`, `target/`, or IDE state in source control.

### 1.3 Milestone structure

Implement the project as four milestones. Each milestone must leave the repository in a runnable, verified state.

| Milestone | Outcome | Includes |
| --------- | ------- | -------- |
| M0: project foundation | A reproducible Jakarta EE web app that builds and starts locally | Repository skeleton, Maven wrapper, `mise`, Docker Compose PostgreSQL, Open Liberty config, health endpoint, placeholder JSF/PrimeFaces pages |
| M1: persistence and security core | Database-backed authentication and authorization with tested service logic | Flyway migrations, JPA entities, password hashing, bootstrap admin, role enforcement, audit foundation, focused unit tests |
| M2: calendar and admin workflows | The calendar and user-management workflows are usable end to end | Calendar event CRUD, viewer/editor/admin behavior, admin user management, validation, audit logging, manual role checks |
| M3: production readiness | The app is packaged, deployable, and recoverable | Docker production image, local Docker runtime test, Railway deployment, custom domain, Dockerized backup/restore, README runbook |

Do not move to the next milestone until the current milestone's verification commands pass and its acceptance criteria are satisfied.

---


## 2. Scope

### 2.1 MVP features

The first deployed version must include:

1. Login with username/password.
2. Logout.
3. Password hashes stored in PostgreSQL.
4. One-time bootstrap admin user from environment variables.
5. Viewer/editor/admin roles.
6. Calendar page using PrimeFaces.
7. View events.
8. Create events as editor/admin.
9. Edit events as editor/admin.
10. Delete events as editor/admin, with admin allowed to delete all events.
11. Admin user-management page.
12. Admin role assignment.
13. Audit log for event and user-management changes.
14. Flyway-managed schema.
15. Health endpoint.
16. Dockerized production build.
17. Railway deployment with custom domain and HTTPS.
18. Basic backup/restore procedure documented.

### 2.2 Explicitly out of scope for v1

Do not build these in the first version:

1. OAuth, SSO, Google login, Microsoft login, or external identity providers.
2. Netlify-hosted frontend.
3. React/Vue/Angular/Svelte frontend.
4. REST API-first architecture.
5. GraphQL.
6. Recurring events / RRULE.
7. ICS import/export.
8. Email reminders.
9. Multi-calendar support.
10. Multiple app instances / horizontal scaling.
11. Kubernetes.
12. Keycloak.
13. Full observability stack.
14. Mobile app.

Add these only after the core app is deployed and stable.

---


## 3. Architecture

The application is a single server-rendered Java web app.

```text
Browser
  -> HTTPS custom domain
  -> Railway edge/proxy
  -> Open Liberty Docker container
  -> Jakarta Faces / PrimeFaces XHTML pages
  -> JSF backing beans
  -> EJB/CDI service layer
  -> JPA EntityManager
  -> PostgreSQL
```

There is no separate frontend service.

### 3.1 Package layout

Use this package structure:

```text
com.example.calendar
  audit
  config
  event
  health
  security
  startup
  user
  util
```

Use feature-oriented packages. Avoid putting every entity in `model`, every service in `service`, and every view bean in `controller` if that makes navigation harder. This app is small; feature locality is more maintainable.

### 3.2 Web resource layout

Use this layout:

```text
src/main/webapp
  index.xhtml
  login.xhtml
  login-error.xhtml
  app
    calendar.xhtml
    admin
      users.xhtml
  WEB-INF
    web.xml
    templates
      main.xhtml
      admin.xhtml
  resources
    css
      app.css
```

Pages under `/app/*` require authentication. Pages under `/app/admin/*` require `ADMIN`.

---



## 21. Security checklist

Before first real use:

1. Use a long random bootstrap password.
2. Change bootstrap admin password immediately after first login.
3. Remove `APP_BOOTSTRAP_ADMIN_PASSWORD` after first setup.
4. Confirm password hashes are not plaintext.
5. Confirm `/app/*` requires login.
6. Confirm `/app/admin/*` requires admin.
7. Confirm service methods have `@RolesAllowed`.
8. Confirm viewers cannot mutate events.
9. Confirm editors cannot manage users.
10. Confirm last admin cannot be disabled.
11. Confirm cookies are HttpOnly.
12. Confirm production cookies are Secure.
13. Confirm URL rewriting is disabled.
14. Do not log passwords.
15. Do not log full database URLs with credentials.
16. Keep one app instance unless session handling is reviewed.

Optional v1.1 hardening:

1. Login attempt throttling by username and IP.
2. Password change screen for all users.
3. Admin-forced password reset flow.
4. Session timeout tuning.
5. Content Security Policy headers.
6. `/ready` endpoint with DB check.
7. Dependency vulnerability scan in CI.

---


## 22. Maintainability rules

Follow these rules throughout implementation:

1. Backing beans coordinate UI only.
2. Services own business rules.
3. Repositories are optional; use `EntityManager` directly in services for v1 unless code becomes noisy.
4. Migrations own schema.
5. JPA entities should not know about PrimeFaces.
6. XHTML should not contain business logic beyond simple rendering checks.
7. Use templates to avoid duplicated page chrome.
8. Keep role names centralized.
9. Keep password hashing parameters centralized.
10. Use small verified checkpoints inside each milestone.
11. Prefer boring code over clever abstractions.
12. Do not introduce Spring Boot into this repo.
13. Do not introduce a JavaScript frontend framework into this repo.
14. Do not introduce Hibernate-specific code unless explicitly required later.

---


## 23. Testing plan

### 23.1 Automated tests for v1

Minimum tests:

1. Password policy validation.
2. Event time validation.
3. Event title validation.
4. Last-admin protection logic.
5. Role helper logic if extracted into pure Java.

Use JUnit 5.

Do not attempt full JSF browser automation in the first pass unless time permits. Manual acceptance checks are acceptable for a personal v1.

### 23.2 Manual acceptance test matrix

Run this matrix before deployment and after deployment.

| Scenario                                     | Expected result                        |
| -------------------------------------------- | -------------------------------------- |
| Logged out user visits `/app/calendar.xhtml` | Redirected to login                    |
| Wrong login                                  | Generic error                          |
| Admin login                                  | Calendar visible                       |
| Viewer login                                 | Calendar visible, edit controls hidden |
| Viewer attempts direct edit action           | Server rejects                         |
| Editor creates event                         | Event appears and persists             |
| Editor edits event                           | Changes persist                        |
| Editor deletes event                         | Event removed                          |
| Admin visits users page                      | Page visible                           |
| Editor visits users page                     | Forbidden or redirected                |
| Admin creates viewer                         | Viewer can log in                      |
| Admin removes last admin role                | Rejected                               |
| App redeploy                                 | Existing users/events remain           |
| `/health`                                    | Returns 200 `ok`                       |

---


## 24. CI plan, optional but recommended

If GitHub Actions is available, add `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
          cache: maven
      - run: ./mvnw clean test package
      - run: docker build -t shared-calendar:ci .
```

Add vulnerability scanning later. Do not block the initial app on a perfect CI pipeline.

---


## 25. Production operating model

For the first deployed version:

```text
Instances: 1
Database: Railway PostgreSQL
Backups: Dockerized manual `pg_dump` first, automated later
Logs: Railway logs
Metrics: Railway metrics
Domain: Railway custom domain
HTTPS: Railway-managed certificate
```

Do not scale to multiple instances until you have reviewed:

1. JSF view state.
2. Session replication/stickiness.
3. File upload/storage if added later.
4. Concurrent edits.
5. Database connection pool sizing.

---


## 26. Future roadmap

### v1.1

1. Password change screen.
2. Login throttling.
3. Better event filtering.
4. User timezone preference.
5. `/ready` endpoint checking DB.
6. Automated nightly backups.
7. Basic CI.

### v1.2

1. ICS export.
2. Calendar list filters.
3. Event color/category.
4. Better audit viewer.
5. Dependency scanning.

### v2

1. Recurring events.
2. Email reminders.
3. Multiple calendars.
4. Public read-only sharing links.
5. Optional migration to a VPS if Railway cost is not acceptable.

---


## 27. Agent completion definition

The local agent is done only when all of these are true:

1. `./mvnw clean test package` passes from a clean checkout.
2. `docker compose up -d postgres` starts local DB.
3. `mise run dev` starts the app locally.
4. `/health` returns `200 ok` locally.
5. First admin bootstraps from env vars.
6. Admin can log in.
7. Viewer/editor/admin role behavior works.
8. Calendar event CRUD works.
9. Admin user management works.
10. Docker image builds.
11. Docker image runs locally.
12. App deploys to Railway.
13. Custom domain works over HTTPS.
14. Backup script has been tested against the local Docker Compose database.
15. README documents setup, deploy, backup, and troubleshooting.
16. There are no accidental `javax.*` enterprise imports.
17. PrimeFaces dependency uses the `jakarta` classifier.
18. JPA code is provider-neutral and does not depend on Hibernate.

---


## 28. Reference links for the agent

Use these documentation sources when stuck:

1. Open Liberty Jakarta EE Web Profile 10.0: `https://openliberty.io/docs/latest/reference/feature/webProfile-10.0.html`
2. Open Liberty container images: `https://openliberty.io/docs/latest/container-images.html`
3. Open Liberty server configuration variables: `https://openliberty.io/docs/latest/reference/config/server-configuration-overview.html`
4. Jakarta EE Tutorial: `https://jakarta.ee/learn/docs/jakartaee-tutorial/current/`
5. PrimeFaces: `https://www.primefaces.org/`
6. PrimeFaces Maven artifact: `https://central.sonatype.com/artifact/org.primefaces/primefaces`
7. Railway Dockerfiles: `https://docs.railway.com/builds/dockerfiles`
8. Railway application host/port troubleshooting: `https://docs.railway.com/networking/troubleshooting/application-failed-to-respond`
9. Railway PostgreSQL: `https://docs.railway.com/databases/postgresql`
10. Railway custom domains: `https://docs.railway.com/networking/domains/working-with-domains`

---


## 29. Final instruction to the local agent

Build the app in the milestone order listed in the main plan index. After each milestone, run the verification commands and confirm the acceptance criteria. If a later task requires changing an earlier architectural decision, stop and record the reason in `README.md` under `Architecture notes` before proceeding.

Do not replace this stack with Spring Boot, React, Node.js, or Netlify. The purpose of this repository is to learn and maintain a Liberty/Jakarta Faces/PrimeFaces application that resembles the JEAP-style environment while remaining simple enough for personal production use.



