# Shared calendar: Jakarta EE + JSF + PrimeFaces implementation plan

**Revision:** self-service multi-calendar product direction

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

Do not move to the next milestone until the current milestone verification commands pass and its acceptance criteria are satisfied.

## Product model

The app is multi-calendar from v1.

1. Only people with an app invitation can create accounts.
2. Signed-in users can generate app-only invitation links.
3. Anyone with an account can create calendars.
4. The creator of a calendar receives the calendar-level `ADMIN` role.
5. Calendar editors and admins can generate app invitation links that also grant `EDITOR` access to that calendar.
6. Calendar roles are `VIEWER`, `EDITOR`, and `ADMIN`; they are scoped to one calendar, not global application roles.
7. Calendars are public by default through long, random, unguessable links.
8. Public links provide read-only access without authentication and must not expose private app routes, member management, invite management, or editing controls.
9. Events are simple dated items. Recurrence, notifications, email delivery, and mobile apps are out of scope for v1.

## Local database decision

Local PostgreSQL runs through Docker Compose with `postgres:17`. PostgreSQL client commands use `docker compose exec postgres` or a temporary `postgres:17` container; host-installed `psql`, `pg_dump`, and `pg_restore` are not prerequisites.

## CI strategy

GitHub PR checks should grow with the milestones:

1. M0: required Maven wrapper build plus a PrimeFaces `jakarta` classifier check.
2. M1: PostgreSQL-backed migration, persistence, and service tests once those tests exist.
3. M2: deterministic app smoke checks for health and stable HTTP routes once the real workflows exist.
4. M3: Docker image build, optional container smoke, Dependency Review, and CodeQL where repository features allow them.

Do not deploy to Railway from pull requests, and do not require secrets for PR checks.

## Source boundaries

This folder is private planning material. Public docs should use final product wording and should not expose planning-only file paths, milestone labels, unfinished notes, or private implementation names.
