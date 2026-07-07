# Shared calendar: Jakarta EE + JSF + PrimeFaces implementation plan

**Revision:** Java 25 LTS runtime + CLI-first reproducible tooling

**Purpose:** Build and deploy a real personal shared-calendar application that is close to a JEAP-style enterprise Java pattern while keeping infrastructure simple.

**Audience:** A local coding agent or developer working in an empty Git repository.

**Target outcome:** A live, password-protected calendar at a custom domain, deployed as one Dockerized Open Liberty web application backed by PostgreSQL.

---

## How to use this plan

Read [Project reference](reference.md) for product, architecture, security, testing, and operations context. Implementation details live in the milestone files below.

## Milestones

Implement in this order:

1. [M0: project foundation](milestones/M0-project-foundation.md)
2. [M1: persistence and security core](milestones/M1-persistence-and-security-core.md)
3. [M2: calendar and admin workflows](milestones/M2-calendar-and-admin-workflows.md)
4. [M3: production readiness](milestones/M3-production-readiness.md)

Do not move to the next milestone until the current milestone verification commands pass and its acceptance criteria are satisfied.

## Local database decision

Local PostgreSQL runs through Docker Compose with `postgres:17`. PostgreSQL client commands use `docker compose exec postgres` or a temporary `postgres:17` container; host-installed `psql`, `pg_dump`, and `pg_restore` are not prerequisites.

## Source boundaries

This folder is private planning material. Public docs should use final product wording and should not expose planning-only file paths or labels.

