# Shared calendar: Jakarta EE + JSF + PrimeFaces implementation plan

**Revision:** implemented self-service multi-calendar repository as of 2026-07-15

**Purpose:** Build and deploy a real shared-calendar application for events such as trips, birthdays, kayaking plans, and friend-group coordination while keeping the enterprise Java stack simple and reproducible.

**Audience:** A local coding agent or developer working in this repository.

**Target outcome:** A live calendar app at a custom domain where invited people can register, create their own calendars, share public read-only calendar links, and invite more people to the app or to edit calendars.

---

## How to use this plan

Read the project reference for product, architecture, security, testing, and operations context. Implementation details live in the milestone files.

## Milestones

Implement in this order:

1. M0: project foundation (implemented)
2. M1: persistence and security core (implemented)
3. M2: calendar and member workflows (implemented)
4. M3: production readiness (repository implementation complete; Railway deployment deferred)

All repository milestones are implemented. Railway deployment and live-domain acceptance remain separate operational work.

## Product model

The app is multi-calendar from v1.

1. Only people with an app invitation can create accounts.
2. Signed-in users can generate app-only invitation links.
3. Anyone with an account can create calendars.
4. The creator of a calendar receives the calendar-level `ADMIN` role.
5. Calendar editors and admins can generate app invitation links that also grant `EDITOR` access to that calendar.
6. Calendar roles are `VIEWER`, `EDITOR`, and `ADMIN`; they are scoped to one calendar, not global application roles.
7. Calendar invitations never grant `VIEWER`; read-only sharing uses the public calendar link.
8. Invitations expire after seven days and become unusable when their creator's account becomes inactive. Their creators can revoke them, calendar admins can list and revoke unused editor invitations, and editor invitations also become unusable when their creator loses edit permission.
9. Invitation acceptance is serialized so a single link cannot be consumed by multiple accounts under concurrent requests.
10. Calendars are public by default through long, random, unguessable links. Public access can be disabled or the token rotated without exposing mutation capabilities.
11. Public links provide read-only access without authentication and must not expose private app routes, member management, invite management, or editing controls.
12. Bootstrap registration is claimed atomically with the first account and remains permanently consumed after success.
13. All-day form dates are inclusive and persist as calendar-zone-normalized, start-inclusive/end-exclusive ranges; timed values reject ambiguous or nonexistent daylight-saving times.
14. Login throttling is source-aware, authenticated session cookies and inactivity roll for 30 days, redeploy requires reauthentication, and health checks require a usable database.
15. Events are simple dated items. Recurrence, notifications, email delivery, and mobile apps are out of scope for v1.

## Local database decision

Local PostgreSQL runs through Docker Compose with `postgres:17`. PostgreSQL client commands use `docker compose exec postgres` or a temporary `postgres:17` container; host-installed `psql`, `pg_dump`, and `pg_restore` are not prerequisites.

## CI strategy

GitHub PR checks now provide:

1. A Maven wrapper build and the focused unit suite.
2. PostgreSQL-backed migration and deterministic Playwright scenarios against Open Liberty.
3. An isolated real-PostgreSQL bootstrap rollback and concurrency scenario against the production image.
4. A production-container smoke check, Dependency Review, and CodeQL.

Do not deploy to Railway from pull requests, and do not require secrets for PR checks.

## Source boundaries

This folder is private planning material. Public docs should use final product wording and should not expose planning-only file paths, milestone labels, unfinished notes, or private implementation names.
