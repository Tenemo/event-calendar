# Shared calendar: Jakarta EE + JSF + PrimeFaces implementation plan

**Revision:** self-service multi-calendar product direction

**Purpose:** Build and deploy a real shared-calendar application for events such as trips, birthdays, kayaking plans, and friend-group coordination while keeping the enterprise Java stack simple and reproducible.

**Audience:** A local coding agent or developer working in this repository.

**Target outcome:** A live calendar app at a custom domain where people can register, create their own calendars, share public read-only calendar links, and invite editors or viewers.

---

## How to use this plan

Read the project reference for product, architecture, security, testing, and operations context. Implementation details live in the milestone files.

## Milestones

Implement in this order:

1. M0: project foundation
2. M1: persistence and security core
3. M2: calendar and member workflows
4. M3: production readiness

Do not move to the next milestone until the current milestone verification commands pass and its acceptance criteria are satisfied.

## Product model

The app is multi-calendar from v1.

1. Anyone who registers can create calendars.
2. The creator of a calendar receives the calendar-level `ADMIN` role.
3. Calendar admins can invite editors and viewers by generating invite links.
4. Calendar roles are `VIEWER`, `EDITOR`, and `ADMIN`; they are scoped to one calendar, not global application roles.
5. Calendars are public by default through long, random, unguessable links.
6. Public links provide read-only access and must not expose private app routes, member management, invite management, or editing controls.
7. Events are simple dated items. Recurrence, notifications, email delivery, and mobile apps are out of scope for v1.

## Local database decision

Local PostgreSQL runs through Docker Compose with `postgres:17`. PostgreSQL client commands use `docker compose exec postgres` or a temporary `postgres:17` container; host-installed `psql`, `pg_dump`, and `pg_restore` are not prerequisites.

## Source boundaries

This folder is private planning material. Public docs should use final product wording and should not expose planning-only file paths, milestone labels, unfinished notes, or private implementation names.
