# calendar.social

calendar.social is a small server-rendered web app for coordinating events with friends and groups. Registered users can create multiple calendars, invite editors, and share a read-only calendar through its calendar link.

## What it does

- Creates shared calendars with `ADMIN` and `EDITOR` memberships.
- Creates timed and all-day events in each calendar's IANA time zone.
- Shares each calendar read-only through its 11-character calendar link.
- Lets an admin disable public access or regenerate the calendar link.
- Uses single-use, seven-day links for registration and editor invitations.
- Changes passwords from the account settings page.
- Issues revocable, indefinite API tokens with the owning account's live permissions.

The app intentionally does not include recurring events, notifications, email delivery, ICS import or export, native clients, read-only accounts, audit history, or account recovery.

## Stack

- Java 25
- Jakarta EE 10, Jakarta Faces, Jakarta REST, and PrimeFaces
- Open Liberty
- MicroProfile OpenAPI
- PostgreSQL 17
- Flyway
- Maven
- Docker and Docker Compose
- Railway for the first hosted deployment

## Local setup

Install [mise](https://mise.jdx.dev/), Docker, and Docker Compose. Then copy `.env.example` to `.env` and run:

```text
mise install
mise run db
mise run dev
```

The default application URL is `http://localhost:9080`, so the local workflow does not require accepting a development certificate. The database health check is available at `http://localhost:9080/health` and returns `ok` only when PostgreSQL is usable.

To run the production container locally instead of Liberty development mode:

```text
mise run docker-up
```

## Deterministic local data

Run this whenever you want to discard local application data and return to the same useful testing state:

```text
mise run reseed-local
```

The task starts local PostgreSQL when needed, refuses non-loopback database hosts, verifies that the target is empty or belongs to calendar.social, rebuilds the pre-launch schema from `V1`, and inserts fixed data in one transaction. It creates the local `admin` account, two additional members, three calendars, seven memberships, and twenty-five realistic events. The fixtures cover public and private calendars, `ADMIN` and `EDITOR` roles, Warsaw and Lisbon time zones, past and future events, events crossing midnight and daylight-saving changes, and single-day and multi-day all-day events. The heavier Weekend adventures calendar spans late July through a January ski trip so calendar navigation and long agendas can be exercised immediately.

Local and pull-request accounts use the same canonical fixture definition at `src/main/resources/db/fixture/canonical-calendar-fixture.sql`. Account names, password hashes, and bearer links remain environment-specific, while calendars, descriptions, memberships, event content, and event times stay identical.

The local Docker application automatically establishes a normal authenticated session for the seeded `admin` account on the first home, sign-in, or authenticated application request. Explicitly signing out suppresses automatic sign-in for that browser session, so the ordinary sign-in flow remains available with `admin` as both the username and password. This shortcut is disabled by default outside Docker Compose and refuses to start when enabled for a non-loopback application origin.

This command permanently deletes all application data in the configured local database. It cannot target a non-loopback host and does not run against the disposable end-to-end database unless that local database is explicitly selected through the `PG*` environment variables.

## First account

Registration is invitation-only. For a fresh database, set `APP_BOOTSTRAP_INVITATION_TOKEN` to a random 43- to 80-character Base64URL value, start the app, and open:

```text
http://localhost:9080/register?token=YOUR_TOKEN
```

The bootstrap invitation can create exactly one account. Its use is recorded atomically in the database and is not restored by deleting users. That first account can create ordinary registration invitations for friends.

Leave `APP_BOOTSTRAP_INVITATION_TOKEN` blank when bootstrap registration should be unavailable.

## Configuration

| Variable | Local default | Purpose |
| --- | --- | --- |
| `APP_BASE_URL` | `http://localhost:9080` | Public origin used in generated links. It must be an HTTP or HTTPS origin without credentials, a path, query parameters, or a fragment. |
| `APP_DEFAULT_TIME_ZONE` | `Europe/Warsaw` | IANA time zone assigned to new calendars. |
| `APP_BOOTSTRAP_INVITATION_TOKEN` | blank | Optional one-time invitation for the first account in a fresh database. |
| `APP_LOCAL_AUTO_SIGN_IN` | `true` in local Docker Compose | Automatically authenticates the deterministic `admin` fixture. It is disabled when unset and can be enabled only with a loopback `APP_BASE_URL`. |
| `APP_LTPA_KEYS_PASSWORD` | `local-development-only` | Password protecting Liberty authentication keys. Use a stable secret in Railway. |
| `APP_SSO_REQUIRES_SSL` | `false` locally | Controls the Secure attribute on the Liberty authentication cookie. Keep this `true` outside loopback-only local development. |
| `PGHOST` | `localhost` | PostgreSQL host. |
| `PGPORT` | `5432` | PostgreSQL port. |
| `PGDATABASE` | `calendar` | PostgreSQL database name. |
| `PGUSER` | `calendar` | PostgreSQL user. |
| `PGPASSWORD` | `calendar` | PostgreSQL password. |
| `PORT` | `9080` | HTTP port. Railway supplies this value in production. |
| `HTTPS_PORT` | `9443` | Local HTTPS port. |

Do not commit `.env` or production secrets.

## Database schema

The project is pre-launch and has one initial schema file:

```text
src/main/resources/db/migration/V1__initial_schema.sql
```

There is deliberately no upgrade path for earlier development schemas. Until the first real users are admitted, change `V1__initial_schema.sql` directly and recreate only this application's database. Never point reset commands at a shared PostgreSQL database or an unverified database name.

Flyway applies the initial schema during startup and refuses to run when the database contains a different migration history. After launch, freeze `V1` and introduce normal forward migrations instead of resetting user data.

The schema contains only application users, API token digests, calendars, memberships, events, outstanding invitations, and the one-row bootstrap state.

## Development commands

```text
mise run package
mise run format
mise run reseed-local
mise run lint-css
mise run lint-workflows
mise run end-to-end
mise run lighthouse
mise run docker-build
```

`mise run package` compiles the app, runs unit tests, checks formatting, builds the WAR, and runs SpotBugs. `mise run end-to-end` builds the production image and runs Chromium against an isolated PostgreSQL database held in temporary storage; it does not use the local development database.

## Pull request checks

Every pull request runs:

- dependency review;
- compilation, unit tests, formatting, SpotBugs, CSS linting, and workflow linting;
- browser tests against an isolated disposable database;
- Lighthouse against an isolated production container.

Lighthouse measures both `/` and `/sign-in` three times and checks their median results. The committed budgets require a performance score of at least `0.90`, first contentful paint at most `2,000 ms`, largest contentful paint at most `2,500 ms`, total blocking time at most `300 ms`, and cumulative layout shift at most `0.10`.

CodeQL also runs on pushes to `master`, weekly, and on demand.

## Access model

Each calendar creator becomes its first `ADMIN`.

- `EDITOR` can view the calendar and create, edit, or delete events.
- `ADMIN` has editor permissions and can change calendar settings and manage memberships.

Every calendar must retain at least one admin. Removing a membership deletes it; accepting a later editor invitation creates it again. Service methods enforce permissions independently of the visible controls.

## API access

Create API tokens under account settings. A token:

- has full read and write access available to its owning account, with no scopes;
- has no expiration time and remains valid until manually revoked;
- uses the account's current calendar roles on every request, so membership and role changes take effect immediately;
- is displayed once when created and cannot be recovered later;
- is stored only as a SHA-256 digest with a short display hint.

Password changes invalidate browser sessions but do not revoke API tokens. Revoke a token explicitly from account settings when it should stop working. Revocation removes its stored digest, and later requests receive `401 Unauthorized`.

Send a token only in the bearer authorization header:

```text
Authorization: Bearer calendar_social_api_YOUR_TOKEN
```

The versioned API starts at `/api/v1`. Browser cookies, calendar links, and invitation tokens do not authenticate API requests. Errors use `application/problem+json`. Calendar and event mutations that can overwrite concurrent changes require the latest strong `ETag` in an `If-Match` header.

The machine-readable OpenAPI contract is available at `/api/openapi`, and its interactive documentation is available at `/api/openapi/ui`.

## Calendar links

Each calendar has one calendar link at `/{calendarLinkToken}`. The token contains 64 random bits encoded as exactly 11 unpadded Base64URL characters.

Members use the same calendar link as anonymous readers. Members see controls allowed by their role; anonymous readers receive a read-only view only while public access is enabled. Disabling public access keeps the calendar link valid for members. Regenerating the calendar link immediately invalidates the previous link for everyone.

Calendar links and invitation links are marked `noindex`, use a no-referrer policy, and must not be logged. Treat each link as a bearer credential and a secret even though this is a low-risk private app.

## Invitations

Any signed-in user can create a registration invitation. Calendar editors and admins can create an invitation granting `EDITOR` membership. An invitation:

- expires after seven days;
- can be used once;
- can be revoked before use;
- stops working if its creator no longer has the required calendar permission;
- is deleted when accepted, revoked, or cleaned up after expiration.

Calendar admins can list and revoke outstanding editor invitations for their calendars.

## Event times

Timed events are entered in the calendar's time zone and stored with their UTC offsets. Ambiguous or nonexistent local times at daylight-saving transitions are rejected.

Both dates entered for an all-day event are inclusive. Persistence uses a start-inclusive, end-exclusive range aligned to calendar-local day boundaries. Changing a calendar's time zone preserves the displayed civil dates of existing all-day events.

## Railway deployment

Railway builds the committed `Dockerfile`, runs one web replica, and checks `/health`. Configure the PostgreSQL variables, `APP_BASE_URL`, `APP_DEFAULT_TIME_ZONE`, `APP_LTPA_KEYS_PASSWORD`, and optionally the bootstrap invitation token on the web service.

When Railway identifies an environment as non-production, every response is marked `noindex, nofollow`.

Pull-request environments clone the `preview-base` service configuration, but Railway gives each PostgreSQL service a fresh volume rather than copying the base volume's data. On startup, the application recognizes only `preview-base` and exact `event-calendar-pr-<number>` environments and provisions the configured non-production account with the full canonical fixture when the database is fresh. The three calendars, seven memberships, and twenty-five events match local development. Ordinary redeployments preserve later changes and restore the configured password hash if it has drifted. An older untouched `Preview calendar` fixture is replaced automatically; a modified preview database is preserved.

The preview login source of truth is `preview-base` → `shared-calendar-web` → Variables. `PREVIEW_VERIFICATION_USERNAME` stores the fixed username and `PREVIEW_VERIFICATION_PASSWORD` stores the fixed password. Do not change either value per pull request or store the plaintext password in the repository. Fixture installation atomically consumes the database bootstrap state; `APP_BOOTSTRAP_INVITATION_TOKEN` is not the preview login. Pull-request code may receive these intentionally reusable preview-only values; they are never used as production credentials.

Unauthenticated sessions expire after ten idle minutes. Successful sign-in extends that server-side idle lifetime to 30 days. Use one application replica because sessions are stored in memory; a restart or redeploy requires users to sign in again.

Before the first public deployment of this cleaned schema, recreate only the PostgreSQL database attached to this application. Confirm the Railway project, environment, service, database name, and current connection before deleting anything.

## Backup and restore

No custom backup tooling is included. Once the app contains valuable data, use Railway PostgreSQL backups or snapshots and verify that they are current before database work. Restore into a non-production environment first when practical.

## Security notes

- Passwords are stored only as salted PBKDF2 hashes.
- Authentication attempts are throttled by normalized username and client source.
- Authenticated session identifiers are rotated and cookies are Secure, HTTP-only, and SameSite `Lax`.
- Password changes invalidate sessions carrying the previous password version.
- Security-sensitive mutations are serialized where concurrent requests could otherwise consume an invitation twice or remove the last admin.
- `/health` checks the database and does not expose application data.

## Known limitations

- Sessions do not survive application restarts or deployments.
- Forgotten-password recovery is not implemented.
- Railway health checks are deployment checks, not continuous monitoring.
- Backups, retention, and uptime alerts are infrastructure settings outside this repository.
- The app is designed for one small friend group installation, not public or commercial scale.
