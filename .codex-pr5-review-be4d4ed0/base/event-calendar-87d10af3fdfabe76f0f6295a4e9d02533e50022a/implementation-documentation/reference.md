# Project reference

This file contains planning context and standards. Milestone files contain the executable implementation detail.

## 1. Non-negotiable decisions

Use these decisions unless the repository owner explicitly changes them later.

| Area                         | Decision                                                                                                     |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Runtime Java                 | Java 25 LTS                                                                                                  |
| Compiler compatibility level | Java 21 by default; later Java 17 if required after all; Java 25 only after MVP if portability is not a goal |
| Build tool                   | Maven                                                                                                        |
| Packaging                    | WAR                                                                                                          |
| Runtime                      | Open Liberty container                                                                                       |
| Enterprise profile           | Jakarta EE 10 Web Profile                                                                                    |
| UI                           | Jakarta Faces 4.0 / JSF + PrimeFaces 15.x `jakarta` classifier                                               |
| Dependency injection         | CDI                                                                                                          |
| Service layer                | Stateless Enterprise Beans / EJB Lite where method security is useful                                        |
| Persistence                  | Jakarta Persistence / JPA, provider-neutral code, Liberty default EclipseLink provider                       |
| Database                     | Dockerized PostgreSQL for local development, Railway PostgreSQL for production                               |
| Migrations                   | Flyway                                                                                                       |
| Auth                         | Username/password with invitation-only registration, no OAuth/SSO                                            |
| Calendar access              | Public read-only token links plus authenticated editor/admin roles                                           |
| Calendar roles               | `VIEWER`, `EDITOR`, `ADMIN`, scoped to one calendar                                                          |
| Deployment                   | Dockerfile to Railway first                                                                                  |
| Frontend hosting             | None; do not use Netlify for the app UI                                                                      |
| Date/time storage            | UTC-aware PostgreSQL `timestamptz`, Java `OffsetDateTime` or carefully tested `Instant`                      |
| Recurrence                   | Out of scope for v1                                                                                          |
| Notifications                | Out of scope for v1                                                                                          |

Everything must be Jakarta-era code. Do not introduce `javax.*` enterprise imports or old JSF XML namespaces. `javax.sql.DataSource` is acceptable because it is part of Java SE.

Use:

```java
import jakarta.*;
```

Use Jakarta Faces namespaces:

```xml
xmlns:h="jakarta.faces.html"
xmlns:f="jakarta.faces.core"
xmlns:p="primefaces"
```

### 1.1 Java 25 policy

Use Java 25 LTS for the local developer JDK and production Open Liberty runtime. Compile application code with Java 21 compatibility by default:

```xml
<maven.compiler.release>21</maven.compiler.release>
```

Do not use preview features. Do not modify global `JAVA_HOME`; use repository-scoped tooling.

### 1.2 CLI-first reproducibility policy

The repository must be usable without IntelliJ. Required workflows must be expressible through source-controlled CLI files.

Required pattern:

```text
Git clone
  -> install mise once if missing
  -> mise trust
  -> mise run setup
  -> mise run db
  -> mise run dev
```

Do not put downloaded JDKs, Maven distributions, PostgreSQL driver jars, PostgreSQL data volumes, `.env`, `target/`, or IDE state in source control.

### 1.3 Milestone structure

Each milestone must leave the repository runnable and verified.

| Milestone                         | Outcome                                                                                                                | Includes                                                                                                                                                             |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M0: project foundation            | A reproducible Jakarta EE web app that builds and starts locally                                                       | Repository skeleton, Maven wrapper, `mise`, Docker Compose PostgreSQL, Open Liberty config, health endpoint, placeholder JSF/PrimeFaces pages, flat responsive shell |
| M1: persistence and security core | Database-backed registration, login, calendars, memberships, public tokens, invitations, and audit foundation          | Flyway migrations, JPA entities, Jakarta Security password hashing, registration, calendar-level authorization, focused tests                                          |
| M2: calendar and member workflows | Public calendar view, authenticated calendar workspace, event CRUD, calendar creation, invite links, member management | PrimeFaces calendar UI, role-aware event actions, settings, invite acceptance, audit logging, manual role checks                                                     |
| M3: production readiness          | The app is packaged, deployable, and recoverable                                                                       | Docker production image, local Docker runtime test, Railway deployment, custom domain, Dockerized backup/restore, README runbook                                     |

## 2. Product scope

### 2.1 MVP features

The first deployed version must include:

1. Invitation-only registration with username/password.
2. Login and logout.
3. Jakarta Security password hashes stored in PostgreSQL.
4. Registered users can create calendars.
5. Calendar creators become calendar admins.
6. Calendar-level roles: `VIEWER`, `EDITOR`, and `ADMIN`.
7. Public read-only calendar links backed by long random tokens.
8. Public calendar pages marked `noindex` and not included in navigation indexes or generated sitemaps.
9. Signed-in users can create app-only invitation links.
10. Calendar editors and admins can create app invitation links that grant editor access to a calendar.
11. App invitation links can be accepted by existing users or newly registered users.
12. Calendar list for signed-in users.
13. Calendar page using PrimeFaces.
14. View events on public links and authenticated calendar pages.
15. Create, edit, and delete events as calendar editor/admin.
16. Calendar settings and member management for calendar admins.
17. Audit log for calendar, invite, member, and event changes.
18. Flyway-managed schema.
19. Health endpoint.
20. Dockerized production build.
21. Railway deployment with custom domain and HTTPS.
22. Basic backup/restore procedure documented.
23. Modern, flat, sleek UI suitable for a practical event calendar.

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
9. Push notifications.
10. Native mobile app.
11. Multiple app instances / horizontal scaling.
12. Kubernetes.
13. Keycloak.
14. Full observability stack.

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
app
  audit
  calendar
  config
  event
  health
  invitation
  membership
  security
  startup
  user
  util
```

Use feature-oriented packages. Backing beans coordinate UI only. Services own business rules.

### 3.2 Web resource layout

Use this layout:

```text
src/main/webapp
  index.xhtml
  login.xhtml
  login-error.xhtml
  register.xhtml
  public-calendar.xhtml
  app
    calendars.xhtml
    calendar.xhtml
    calendar-members.xhtml
    calendar-settings.xhtml
    invitations.xhtml
  WEB-INF
    web.xml
    templates
      main.xhtml
  resources
    css
      app.css
```

Public calendar routes must be reachable without authentication. Authenticated app routes under `/app/*` require login. Calendar mutation and member management are enforced in services with calendar-specific authorization.

Jakarta Faces extensionless routing is enabled. User-facing links should prefer clean paths such as `/login`, `/register`, `/app/calendars`, `/app/calendar`, `/app/calendar-members`, `/app/calendar-settings`, and `/app/invitations`; `.xhtml` remains an implementation file suffix, not the canonical browser route.

### 3.3 URL model

Use a public calendar URL shape that is easy to share and hard to guess:

```text
/calendar/{publicToken}
```

Do not expose sequential calendar ids in public read URLs. Do not use calendar names or slugs as access secrets.

## 4. Authorization model

Application authentication answers "who is signed in." Calendar authorization answers "what can this user do on this calendar."

1. Public visitors with a valid public token may view events only.
2. Signed-in users may create calendars.
3. Calendar creators receive `ADMIN` membership on the new calendar.
4. `VIEWER` remains a calendar-scoped role, but public calendar viewing must not require authentication.
5. `EDITOR` members can create, edit, and delete events.
6. `ADMIN` members can do everything editors can do, plus manage calendar settings and members.
7. At least one active `ADMIN` membership must remain on every active calendar.
8. Signed-in users can create app-only invitations.
9. Calendar editors and admins can create app invitations that grant editor access to that calendar.
10. UI controls may hide unavailable actions, but services must enforce membership and role checks.
11. Public tokens and invite tokens are bearer secrets; store only random, unguessable values and allow rotation/revocation.

## 5. Security checklist

Before first real use:

1. Confirm password hashes are not plaintext.
2. Confirm registration requires a valid app invitation.
3. Confirm public calendar pages are read-only.
4. Confirm public calendar pages include `noindex`.
5. Confirm public links use random UUID or stronger tokens, not database ids.
6. Confirm invite links use random UUID or stronger tokens and can be revoked.
7. Confirm `/app/*` requires login.
8. Confirm service methods enforce calendar membership.
9. Confirm public visitors cannot mutate events.
10. Confirm viewers cannot mutate events.
11. Confirm editors cannot manage members or settings.
12. Confirm last calendar admin cannot be disabled or demoted.
13. Confirm cookies are HttpOnly.
14. Confirm production cookies are Secure.
15. Confirm URL rewriting is disabled.
16. Do not log passwords, public tokens, invite tokens, or full database URLs with credentials.
17. Do not log app invitation tokens.
18. Keep one app instance unless session handling is reviewed.

Optional v1.1 hardening:

1. Login attempt throttling by username and IP.
2. Registration throttling.
3. Password change screen.
4. Session timeout tuning.
5. Content Security Policy headers.
6. `/ready` endpoint with DB check.
7. Dependency vulnerability scan in CI.

## 6. Maintainability rules

1. Backing beans coordinate UI only.
2. Services own business rules.
3. Repositories are optional; use `EntityManager` directly in services for v1 unless code becomes noisy.
4. Migrations own schema.
5. JPA entities should not know about PrimeFaces.
6. XHTML should not contain business logic beyond simple rendering checks.
7. Use templates to avoid duplicated page chrome.
8. Keep role names centralized.
9. Keep password policy and Jakarta Security password-hash parameters centralized.
10. Keep public token and app invitation token generation centralized.
11. Use small verified checkpoints inside each milestone.
12. Prefer boring code over clever abstractions.
13. Do not introduce Spring Boot into this repo.
14. Do not introduce a JavaScript frontend framework into this repo.
15. Do not introduce Hibernate-specific code unless explicitly required later.

## 7. UI direction

The app should feel modern, flat, sleek, and practical:

1. Build app screens first, not a marketing landing page.
2. Prefer open layouts, clear tables/lists, and restrained panels over decorative card-heavy pages.
3. Use a white/light neutral background, strong readable text, subtle borders, and one or two accent colors.
4. Keep PrimeFaces components styled consistently with the app shell.
5. Use sentence-case UI copy.
6. Avoid oversized hero sections, decorative gradients, nested cards, and one-note color palettes.
7. Make mobile web responsive, but do not build a mobile app.

## 8. Testing plan

Minimum automated tests:

1. Password policy validation.
2. Invitation-only registration validation.
3. Calendar creation grants the creator `ADMIN`.
4. Public token generation is non-blank, unique at service level, and not derived from sequential ids.
5. Invite token acceptance assigns the intended calendar role.
6. Event title validation.
7. Event time validation.
8. Calendar role checks for public viewer, member viewer, editor, and admin paths.
9. Last-calendar-admin protection.
10. Timezone display helpers if extracted into pure Java.

Manual acceptance checks before deployment and after deployment:

| Scenario                                   | Expected result                                           |
| ------------------------------------------ | --------------------------------------------------------- |
| Public visitor opens valid calendar link   | Calendar visible read-only                                |
| Public visitor opens invalid calendar link | Generic not-found page                                    |
| Public visitor tries mutation URL/action   | Rejected                                                  |
| Signed-in user creates app invitation      | Single-use account link is generated                      |
| New user registers with app invitation     | Account is created                                        |
| Registered user creates calendar           | User becomes calendar admin                               |
| Calendar editor creates editor invitation  | Invite link is generated                                  |
| Invitee accepts editor invitation          | Invitee can edit that calendar                            |
| Public visitor opens public calendar       | Calendar visible without sign-in                          |
| Editor creates event                       | Event appears and persists                                |
| Editor edits event                         | Changes persist                                           |
| Editor deletes event                       | Event removed                                             |
| Calendar admin manages members             | Changes persist and are audited                           |
| Calendar admin removes last admin role     | Rejected                                                  |
| App redeploy                               | Existing users, calendars, memberships, and events remain |
| `/health`                                  | Returns 200 `ok`                                          |

## 9. Production operating model

For the first deployed version:

```text
Instances: 1
Database: Railway PostgreSQL
Backups: Dockerized manual pg_dump first, automated later
Logs: Railway logs
Metrics: Railway metrics
Domain: Railway custom domain
HTTPS: Railway-managed certificate
```

Do not scale to multiple instances until you have reviewed JSF view state, session stickiness, concurrent edits, and database connection pool sizing.

## 10. Future roadmap

### v1.1

1. Password change screen.
2. Login and registration throttling.
3. Public link rotation UI.
4. Better event filtering.
5. User timezone preference.
6. `/ready` endpoint checking DB.
7. Automated nightly backups.
8. Basic CI.

### v1.2

1. ICS export.
2. Event color/category.
3. Better audit viewer.
4. Dependency scanning.

### v2

1. Recurring events.
2. Email reminders.
3. Private calendars.
4. Public read-only sharing links with optional display names.
5. Optional migration to a VPS if Railway cost is not acceptable.

## 11. Agent completion definition

The local agent is done only when all of these are true:

1. `./mvnw clean test package` passes from a clean checkout.
2. `docker compose up -d postgres` starts local DB.
3. `mise run dev` starts the app locally.
4. `/health` returns `200 ok` locally.
5. Users can register.
6. Users can log in.
7. Registered users can create calendars.
8. Calendar creators become calendar admins.
9. Public calendar links work read-only.
10. Calendar invite links work for viewer/editor access.
11. Calendar role behavior works.
12. Event CRUD works for editor/admin and is rejected for viewer/public users.
13. Calendar member management works for calendar admins.
14. Docker image builds.
15. Docker image runs locally.
16. App deploys to Railway.
17. Custom domain works over HTTPS.
18. Backup script has been tested against the local Docker Compose database.
19. README documents setup, deploy, backup, troubleshooting, roles, invitations, public links, and known limitations.
20. There are no accidental `javax.*` enterprise imports.
21. PrimeFaces dependency uses the `jakarta` classifier.
22. JPA code is provider-neutral and does not depend on Hibernate.

## 12. Reference links

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
