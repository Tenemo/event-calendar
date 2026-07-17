# Project specification

This file is the authoritative specification for product behavior, architecture, security, user experience, testing, deployment, and operations. Milestone files contain dependency-ordered implementation and verification steps only. A milestone must not introduce requirements that are absent here.

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
| Auth                         | Username/password with invitation-only registration, signed-in password change, source-aware login throttling, no OAuth/SSO |
| Calendar access              | Root `/{calendarToken}` read-only bearer links plus authenticated editor/admin roles                         |
| Calendar roles               | `EDITOR`, `ADMIN`, scoped to one calendar                                                                    |
| Deployment                   | Dockerfile to Railway first                                                                                  |
| Frontend hosting             | None; do not use Netlify for the app UI                                                                      |
| Date/time storage            | Timed instants in `timestamptz`; all-day civil dates as calendar-zone-normalized inclusive/exclusive bounds  |
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

### 1.3 Implementation phases

Each implementation phase must leave the repository runnable and verified. The phase files sequence work; the requirements themselves remain in this specification.

| Milestone                         | Outcome                                                                                                                | Includes                                                                                                                                                             |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M0: project foundation            | A reproducible Jakarta EE web app that builds and starts locally                                                       | Repository skeleton, Maven wrapper, `mise`, Docker Compose PostgreSQL, Open Liberty config, health endpoint, placeholder JSF/PrimeFaces pages, flat responsive shell |
| M1: persistence and security core | Database-backed registration, login, password change, calendars, memberships, public tokens, invitations, and audit foundation | Flyway migrations, JPA entities, Jakarta Security password hashing, session revocation, registration, calendar-level authorization, focused tests |
| M2: calendar and account workflows | One canonical calendar view, account settings, event CRUD, calendar creation, invite links, and member management     | PrimeFaces calendar and account UI, bearer-link reads, role-aware event actions, settings, invite acceptance, audit logging, manual role checks |
| M3: production readiness          | The app is packaged, deployable, and recoverable                                                                       | Docker production image, local Docker runtime test, Railway deployment, custom domain, Dockerized backup/restore, README runbook                                     |

## 2. Product scope

### 2.1 MVP features

The first deployed version must include:

1. Invitation-only registration with username/password.
2. Login and logout.
3. Jakarta Security password hashes stored in PostgreSQL.
4. Signed-in users can reset/change their own password after confirming their current password.
5. Registered users can create calendars.
6. Calendar creators become calendar admins.
7. Calendar-level roles: `EDITOR` and `ADMIN`.
8. One canonical root calendar URL backed by an 11-character unpadded Base64URL token encoding 64 cryptographically random bits and shared directly from the editor's browser.
9. Canonical calendar pages marked `noindex` and not included in navigation indexes or generated sitemaps.
10. Signed-in users can create app-only invitation links.
11. Calendar editors and admins can create app invitation links that grant editor access to a calendar.
12. There is no read-only membership role. Read-only sharing uses the calendar's bearer link.
13. App invitation links can be accepted by existing users or newly registered users.
14. Calendar list for signed-in users.
15. Calendar page using PrimeFaces.
16. View events on the same canonical calendar page, with member actions determined by server-enforced membership.
17. Create, edit, and delete events as calendar editor/admin.
18. Calendar settings and member management for calendar admins.
19. Audit log for account password, calendar, invite, member, and event changes.
20. Flyway-managed schema.
21. Database-aware health endpoint.
22. Login throttling.
23. Dockerized production build.
24. Railway deployment with custom domain and HTTPS.
25. Basic backup/restore procedure documented.
26. Modern, flat, sleek UI suitable for a practical event calendar.

Final v1 invariants:

1. App-only and calendar-editor invitations expire exactly seven days after creation, cannot be issued with a longer lifetime, are capped by the database, and are serialized during acceptance.
2. Every invitation becomes invalid when its creator's account becomes inactive. Calendar invitations grant only `EDITOR`, also become invalid when their creator loses edit permission, and can be listed and revoked by calendar admins.
3. `/{calendarToken}` is the one canonical URL for members and anonymous readers. The token is exactly 11 unpadded Base64URL characters and there is no `/calendar/` prefix. Public access can be disabled without changing that URL, and regeneration invalidates the previous URL immediately for everyone.
4. Bootstrap admission is claimed in the account-creation transaction, rolls back with a failed registration, and is permanently consumed after the first successful account.
5. All-day UI dates are inclusive while storage ends at the exclusive start of the following calendar-local day. Services receive civil dates directly, use the calendar's Java time-zone rules, and reject stale event forms after a concurrent calendar-settings change.
6. Anonymous sessions are non-persistent and expire after 30 minutes of inactivity. Successful authentication extends that session to a rolling 30 days, while in-memory sessions require reauthentication after restart or redeploy.
7. `/health` returns success only while PostgreSQL is usable.
8. Password changes require the signed-in user's current password, matching new-password confirmation, the current password policy, and a genuinely different password. Success is audited without secrets, increments a database-backed password version, ends the changing session, invalidates every older session on its next request, and requires reauthentication with the new password.

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
15. Forgotten-password recovery without a signed-in session or a verified recovery channel.
16. Email-based password-reset delivery.

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
  sign-in-error.xhtml
  register.xhtml
  calendar.xhtml
  app
    account-settings.xhtml
    calendars.xhtml
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

The canonical `/{calendarToken}` route must be reachable without authentication. It renders read-only content to nonmembers while public access is enabled and member controls to active editors and admins. Authenticated app routes under `/app/*` require login. Account password changes are enforced in the user service, while calendar mutation and member management are enforced in services with calendar-specific authorization.

Jakarta Faces extensionless routing is enabled. User-facing links should prefer clean paths such as `/login`, `/register`, `/app/calendars`, `/app/account-settings`, `/{calendarToken}`, `/app/calendar-members`, `/app/calendar-settings`, and `/app/invitations`; `.xhtml` remains an implementation file suffix, not the canonical browser route.

### 3.3 URL model

Use one canonical calendar URL shape that is easy to share and hard to guess:

```text
/{calendarLinkToken}
```

The token is exactly 11 characters, produced by encoding eight cryptographically random bytes without Base64 padding. Its first ten characters use `[A-Za-z0-9_-]`; its final character must be one of `AEIMQUYcgkosw048`, the canonical values possible when encoding exactly eight bytes. Reject noncanonical 11-character lookalikes before database access. The one-segment 11-character root namespace is reserved for canonical calendars. Do not expose sequential calendar ids in calendar read URLs. Do not use calendar names or slugs as access secrets. Active editors and admins use this same URL, so the address visible in their browser is exactly the address they share.

Reject any path that does not match the exact canonical shape before database access. Look up valid-looking tokens through the unique indexed column. Before lookup, limit one verified client source to 300 attempts per minute, allow at most 16 canonical-calendar requests to execute concurrently, and bound in-memory source state to 10,000 entries. Missing, disabled, and regenerated tokens return the same clear `404`; throttled requests return a generic `429` with `Retry-After`. Never echo a candidate token in either response. These controls defend against online iteration and bound application/database work, but an upstream application-layer DDoS service is still required to absorb large distributed or volumetric attacks before Railway.

### 3.4 Runtime and persistence requirements

1. The repository must provide Maven Wrapper, repository-scoped Java configuration, portable `mise` tasks, Docker Compose PostgreSQL 17, and a thin portable Java orchestration helper.
2. Open Liberty must bind to every container interface, use the injected `PORT`, deploy the WAR at `/`, disable URL-rewritten sessions, and configure unconditionally Secure, HTTP-only, SameSite `Lax` session cookies.
3. The PostgreSQL driver must be available to Liberty as a server resource. Downloaded drivers and generated Liberty state belong under ignored build output.
4. Flyway owns every schema change and runs before the application accepts traffic. Jakarta Persistence schema generation remains disabled.
5. Persistence code must remain provider-neutral and use the Jakarta Persistence XML schema supported by the runtime.
6. The schema contains users, permanent registration-bootstrap state, calendars, calendar memberships, unified invitations, events, and audit records. Calendar public tokens are unique and constrained to the canonical 11-character format. The user record contains a monotonically increasing password version used to revoke older authenticated sessions.
7. Calendars and events use optimistic versions for user-facing edit conflicts. Invitation admission, bootstrap admission, membership creation, and password replacement use database serialization where concurrent requests would otherwise violate a single-use or identity invariant.
8. Timed values persist as offset-aware instants. All-day values persist as calendar-zone-derived start-inclusive and end-exclusive instants.
9. Database migrations, entities, service rules, and restore tooling must agree on the same schema. Application startup must fail when migrations fail.

### 3.5 Authentication and account lifecycle

Application authentication creates the global `USER` identity only. Calendar `EDITOR` and `ADMIN` roles are loaded and enforced separately from calendar membership records.

Registration requirements:

1. Accept an invitation token, username, display name, password, and initial calendar name.
2. Normalize usernames consistently and reject blank or schema-oversized account fields.
3. Require an unused invitation or atomically claim the permanent first-account bootstrap row.
4. Hash accepted passwords with Jakarta Security `Pbkdf2PasswordHash` using PBKDF2-HMAC-SHA256, 600,000 iterations, a 32-byte salt, and a 32-byte derived key.
5. Require passwords from 8 through 512 characters with at least one uppercase letter and one digit, and reject a password equal to the username.
6. Create the user's initial calendar, grant that user `ADMIN`, apply any calendar-editor invitation, consume the invitation, and record non-secret audit details in the same logical workflow.
7. Authenticate the new account when possible, rotate the pre-authentication session identifier, and bind the current database password version to the authenticated session.

Login and session requirements:

1. Return the same sign-in failure for missing, inactive, and incorrectly authenticated accounts.
2. Perform a real password-hash verification for existing accounts and a fixed dummy-hash verification for missing accounts.
3. Track failures by normalized username and client source. Five failures for one username/source pair in 15 minutes block that pair for 15 minutes; 25 source-wide failures in the same window block that source for 15 minutes. On Railway, resolve the source from the platform-controlled leftmost `X-Forwarded-For` address only when `RAILWAY_ENVIRONMENT_ID` is present; elsewhere ignore forwarded addresses and use the direct TCP peer.
4. Rotate the session identifier after authentication and bind the user's current password version to the session.
5. Keep anonymous cookies non-persistent with a 30-minute server-side inactivity timeout. Only after successful authentication, set the server-side timeout to 30 days and refresh authenticated application and canonical-calendar cookies with a rolling 30-day lifetime.
6. Compare the session's password version with the active user record before protected application or canonical-calendar processing. A mismatch invalidates the session before the request reaches a backing bean or service.
7. Redirect stale protected application sessions to sign-in. A stale canonical-calendar session is discarded and the same URL is re-evaluated as anonymous read-only access.
8. Keep sessions in memory for one application instance. Restart or redeploy requires reauthentication and must not affect persisted account or calendar data.

Signed-in password reset and change requirements:

1. Expose the flow from `/app/account-settings`; anonymous callers are rejected by the authenticated route constraint and the service still requires an active user.
2. Require the current password, a new password, and an exact new-password confirmation.
3. Lock and reload the active account before verifying the current password so concurrent changes cannot both succeed against one old credential.
4. Apply the same centralized password policy used for registration and reject reuse of the current password.
5. Replace only the password hash, increment the password version, update the account timestamp, and write an audit entry that contains no password, hash, or session identifier.
6. Invalidate the changing session immediately and require sign-in with the new password. Every other session becomes invalid when its next request observes the incremented password version.
7. Use clear validation messages for the signed-in user without exposing passwords in URLs, logs, audit details, or rendered markup.
8. The product UI calls this action "Change password" because it requires the current password. Do not present this authenticated reset/change flow as forgotten-password recovery. Recovery without a valid session remains unavailable until the application has a verified recovery channel.

## 4. Authorization model

Application authentication answers "who is signed in." Calendar authorization answers "what can this user do on this calendar."

1. Any visitor with the current token may view events only while public access is enabled.
2. Signed-in users may create calendars.
3. Calendar creators receive `ADMIN` membership on the new calendar.
4. There is no read-only membership. A signed-in nonmember has the same read-only bearer-link access as an anonymous visitor.
5. `EDITOR` members can create, edit, and delete events and regenerate the canonical calendar URL.
6. `ADMIN` members can do everything editors can do, plus manage calendar settings and members, including enabling or disabling public access.
7. Active editors and admins may continue using the canonical URL while public access is disabled; all nonmembers receive the same clear link-unavailable `404` response.
8. At least one active `ADMIN` membership must remain on every active calendar.
9. Signed-in users can create app-only invitations that expire exactly seven days after creation.
10. Calendar editors and admins can create invitations that grant editor access to that calendar and expire exactly seven days after creation.
11. Invitation creators may revoke unused links, and calendar admins may list and revoke unused editor invitations for calendars they administer.
12. Revalidate the creator's current edit permission during editor-invitation acceptance and serialize acceptance so exactly one account can consume a link.
13. UI controls may hide unavailable actions, but services must enforce membership and role checks.
14. Calendar and invite tokens are bearer secrets; calendar tokens contain exactly 64 random bits while invitation tokens retain 256 random bits. Never log either token type, and support calendar URL regeneration and invitation revocation.

## 5. Security checklist

Before first real use:

1. Confirm password hashes are not plaintext.
2. Confirm registration requires a valid app invitation.
3. Confirm nonmember access to canonical calendar pages is read-only.
4. Confirm canonical calendar pages include `noindex`.
5. Confirm calendar links use exactly 64 cryptographically random bits encoded as 11 unpadded Base64URL characters at the root, never database ids, names, or slugs.
6. Confirm invite links use random UUID or stronger tokens, expire after seven days, become invalid when their creator is inactive, and can be revoked by their creator or the relevant calendar admin.
7. Confirm `/app/*` requires login and that exact `/{calendarToken}` routes expose member actions only after service-enforced membership checks.
8. Confirm service methods enforce calendar membership for every mutation.
9. Confirm public visitors cannot mutate events.
10. Confirm signed-in nonmembers cannot mutate events through a bearer link.
11. Confirm editors cannot manage members or settings.
12. Confirm last calendar admin cannot be disabled or demoted.
13. Confirm cookies are HttpOnly.
14. Confirm production cookies are Secure.
15. Confirm URL rewriting is disabled.
16. Do not log passwords, calendar tokens, invite tokens, or full database URLs with credentials.
17. Do not log app invitation tokens.
18. Keep one app instance unless session handling is reviewed.
19. Throttle repeated login failures by normalized username and client source without revealing whether an account exists or allowing one source to lock out other sources.
20. Keep anonymous sessions non-persistent with a 30-minute inactivity timeout. Refresh authenticated cookies on app and canonical calendar activity, use a 30-day authenticated inactivity timeout, and require reauthentication after application restart or redeploy while sessions remain in memory.
21. Make `/health` fail when the database is unavailable and bound connection acquisition within the probe timeout.
22. Make bootstrap consumption permanent after successful registration and transactional so a failed attempt releases the claim.
23. Revalidate editor-invitation creator permission inside the serialized membership grant.
24. Require the current password and matching confirmation before changing a password, and reject current-password reuse.
25. Serialize password replacement for one account and increment its password version atomically with the new hash.
26. Invalidate the changing session immediately and reject every older session before protected request processing.
27. Keep passwords, password hashes, password versions, and session identifiers out of URLs, audit details, and logs.
28. Reject malformed root paths without calendar database access; source-rate-limit valid-looking paths through the same Railway-aware, spoof-resistant client-source resolver used by login, cap concurrent calendar rendering, and keep throttle memory bounded.
29. Use generic `404` and `429` responses that do not reveal whether a guessed token exists or echo the candidate token.
30. Do not describe in-process throttling as complete DDoS protection. Railway supplies network-layer protection but no application-layer WAF; use an upstream application-layer edge and prevent direct-origin bypass when the threat requires it.

Optional v1.1 hardening:

1. Registration throttling.
2. Content Security Policy headers.
3. Dependency vulnerability scan in CI.

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
10. Keep calendar-token format/generation and invitation-token generation centralized without weakening invitation tokens when calendar URLs change.
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

### 7.1 Page responsibilities

1. The shared template provides the document title, stylesheet, messages, brand, primary navigation, sign-in state, sign-out action, skip link, and main content region without duplicating page chrome.
2. Registration collects invitation token, username, display name, password, and initial calendar name, and shows generic user-readable admission failures.
3. Login collects username and password, preserves an invitation continuation when present, and never identifies which credential was wrong.
4. Account settings identifies the signed-in account, explains that success signs every session out, and collects current password, new password, and confirmation with correct browser autocomplete attributes.
5. The calendar list shows every active membership, its role, a direct canonical-calendar link, and calendar creation.
6. The canonical calendar page loads by bearer token, always includes `noindex, nofollow`, renders events in the calendar time zone, and adds event and link-regeneration controls only for active editors and admins.
7. Calendar settings lets admins edit name, description, IANA time zone, and public-access enablement without creating a second sharing URL.
8. Member management lets admins view active and inactive members, change roles, remove access, reactivate access, and inspect or revoke unused editor invitations for that calendar.
9. Invitation management lets signed-in users create app invitations and authorized members create editor invitations, then displays copyable links, expiry, status, and allowed revocation controls without email delivery.
10. Destructive event, member, invitation, and public-link actions require an appropriate confirmation or explicit submission and return focus and messages predictably.

## 8. Testing plan

Minimum automated tests:

1. Password policy validation.
2. Invitation-only registration validation.
3. Calendar creation grants the creator `ADMIN`.
4. Calendar token generation produces unique 11-character unpadded Base64URL values from eight random bytes and is not derived from sequential ids; invitation tokens remain 43 characters from 32 random bytes.
5. Invite token acceptance assigns the intended calendar role.
6. Event title validation.
7. Event time validation.
8. Calendar access checks for anonymous readers, signed-in nonmembers, removed editors, active editors, and admins.
9. Last-calendar-admin protection.
10. Timezone display helpers if extracted into pure Java.
11. Inclusive all-day dates and exclusive stored ends across short, long, skipped, and repeated civil days.
12. Exact seven-day invitation expiry in the service and database, migration capping of older invitations, creator permission revalidation, administrator visibility/revocation, and concurrent single-use acceptance.
13. Permanent atomic bootstrap consumption, including rollback and a real PostgreSQL race.
14. Source-aware login throttling with generic missing-user behavior.
15. Non-persistent 30-minute anonymous sessions, rolling authenticated cookies, 30-day authenticated inactivity, restart reauthentication, and database-aware health.
16. Password-change validation for wrong current password, mismatched confirmation, policy failures, password reuse, successful hash replacement, password-version increment, audit safety, and database locking.
17. Browser-level password change from two authenticated sessions, including immediate logout, old-password rejection, new-password acceptance, and stale-session invalidation.
18. Exact root-route recognition, legacy `/calendar/` rejection, and malformed-path rejection before calendar lookup.
19. Railway direct/CDN/internal forwarded chains, spoof-resistant local fallback, per-source calendar-link throttling, bounded source state, global concurrency admission, generic overload responses, and permit release on every success and exception path.

Manual acceptance checks before deployment and after deployment:

| Scenario                                   | Expected result                                           |
| ------------------------------------------ | --------------------------------------------------------- |
| Public visitor opens valid calendar link   | Calendar visible read-only                                |
| Public visitor opens invalid calendar link | Clear link-unavailable `404` page                         |
| Visitor opens a legacy `/calendar/{token}` path | Route is not treated as a calendar and returns `404` |
| One client iterates valid-looking root tokens | Requests are throttled with a generic retryable `429` before unbounded database work |
| Public visitor tries mutation URL/action   | Rejected                                                  |
| Signed-in user creates app invitation      | Single-use account link is generated                      |
| New user registers with app invitation     | Account is created                                        |
| Registered user creates calendar           | User becomes calendar admin                               |
| Calendar editor creates editor invitation  | Invite link is generated                                  |
| Invitee accepts editor invitation          | Invitee can edit that calendar                            |
| Removed editor uses a previously created invitation | Rejected                                         |
| Two users concurrently accept one invitation | Exactly one acceptance succeeds                         |
| Calendar admin views or revokes another editor's unused invitation | Invitation is visible and revocable       |
| Public visitor opens public calendar       | Calendar visible without sign-in                          |
| Admin disables public access or editor regenerates URL | Disabled or previous URL returns a clear link-unavailable `404` |
| Editor creates event                       | Event appears and persists                                |
| Editor saves all-day event across DST      | Inclusive dates display unchanged; exclusive bounds persist |
| Editor submits an event after another admin changes calendar settings | Rejected with a reload message; no date is shifted |
| Editor edits event                         | Changes persist                                           |
| Editor deletes event                       | Event removed                                             |
| Calendar admin manages members             | Changes persist and are audited                           |
| Calendar admin removes last admin role     | Rejected                                                  |
| App redeploy                               | Existing users, calendars, memberships, and events remain |
| App redeploy with authenticated browser    | Reauthentication is required; database records remain      |
| Signed-in user submits a wrong current password | Password remains unchanged and a clear error is shown |
| Signed-in user changes to a valid new password | Current session ends; old password fails and new password succeeds |
| Another session remains open during password change | Its next protected request redirects to sign-in before rendering protected content |
| `/health` with available database          | Returns 200 `ok`                                          |
| `/health` with unavailable database        | Returns 503 without exposing connection details           |

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

Production packaging and deployment requirements:

1. Build the WAR in a disposable Java 25 stage and run it in the pinned Open Liberty Java 25 image without Maven or application source in the runtime stage.
2. Copy only Liberty configuration, declared server features, the Maven-managed PostgreSQL driver, and the WAR into the runtime image. Never bake environment secrets or private planning material into the image.
3. Emit structured Liberty logs to standard output and standard error without passwords, database URLs containing credentials, calendar tokens, invitation tokens, or session identifiers.
4. Keep Railway build, start, restart, and deployment-health configuration in repository-owned configuration-as-code where Railway supports it.
5. Deploy production from pushes or merges to `master`; do not create preview deployments or deploy from pull-request workflows.
6. Use Railway's injected `PORT`, private PostgreSQL service references, persistent PostgreSQL storage, managed HTTPS, and the `calendar.social` custom domain.
7. Set `APP_BASE_URL=https://calendar.social` in production. Session cookies remain Secure without an environment-controlled downgrade path.
8. Keep the bootstrap invitation secret only until the first account is created, then clear it and redeploy. Permanent database bootstrap consumption remains authoritative.
9. Gate a deployment on `/health`, then use an external HTTPS monitor for continuous outage detection because Railway deployment probes are not ongoing monitoring.
10. Verify account, calendar, membership, invitation, event, audit, and password-version persistence after a redeploy; expect all in-memory sessions to require sign-in again.
11. Treat the in-process source and concurrency limits as origin load shedding. For application-layer DDoS protection, proxy the custom domain through a WAF/rate-limiting edge and remove any generated Railway domain that would bypass it.

Backup and restore requirements:

1. Use PostgreSQL 17 clients in Docker so host-installed PostgreSQL programs are unnecessary.
2. Create a nonempty custom-format dump in a unique partial file and atomically publish it only after success.
3. Require an explicit target database name before restore and validate the archive before running a single-transaction guarded restore.
4. Stop the application before restoring a real database.
5. Verify restore into a fresh tmpfs PostgreSQL service and compare row counts for every application table and Flyway history.
6. Pass remote database passwords through the client container environment rather than command-line arguments or logs.

CI requirements:

1. Run the Maven wrapper build and PrimeFaces Jakarta-classifier check.
2. Run deterministic PostgreSQL-backed migrations and browser workflows against Open Liberty.
3. Run isolated real-PostgreSQL bootstrap rollback and concurrency coverage against the production image.
4. Build and smoke-test the production container against PostgreSQL.
5. Run Dependency Review for pull requests and CodeQL for pull requests, pushes to `master`, and the configured schedule.
6. Use read-only workflow permissions where possible, do not require production secrets for pull requests, and never add retries to conceal flaky tests.

## 10. Future roadmap

### v1.1

1. Registration throttling.
2. Better event filtering.
3. User timezone preference.
4. Automated nightly backups.
5. Account recovery after a verified recovery channel is designed.

### v1.2

1. ICS export.
2. Event color/category.
3. Better audit log page.
4. Broader dependency reporting and artifact provenance beyond the existing Dependency Review and CodeQL checks.

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
7. Signed-in users can change their password and every older session is invalidated.
8. Registered users can create calendars.
9. Calendar creators become calendar admins.
10. Public calendar links work read-only.
11. Calendar invite links grant editor access only; read-only sharing uses public links.
12. Calendar role behavior works.
13. Event CRUD works for editor/admin and is rejected for anonymous readers and signed-in nonmembers.
14. Calendar member management works for calendar admins.
15. Docker image builds.
16. Docker image runs locally.
17. App deploys to Railway.
18. Custom domain works over HTTPS.
19. Backup script has been tested against the local Docker Compose database.
20. README documents setup, deploy, backup, troubleshooting, roles, password changes, invitations, public links, and known limitations.
21. There are no accidental `javax.*` enterprise imports.
22. PrimeFaces dependency uses the `jakarta` classifier.
23. JPA code is provider-neutral and does not depend on Hibernate.
24. Flyway migrations through version 11 apply successfully, including one-time rotation of existing calendar URLs into the compact root format and the enforced seven-day invitation cap.
25. The focused unit suite, primary browser scenarios, and isolated real-PostgreSQL bootstrap race pass without retries.

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
11. Railway networking specifications and DDoS limits: `https://docs.railway.com/networking/public-networking/specs-and-limits`
12. OWASP denial-of-service guidance: `https://cheatsheetseries.owasp.org/cheatsheets/Denial_of_Service_Cheat_Sheet.html`
