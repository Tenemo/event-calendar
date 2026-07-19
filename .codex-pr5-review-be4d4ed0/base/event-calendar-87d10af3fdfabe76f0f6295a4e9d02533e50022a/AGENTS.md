# Agent instructions

## Source boundaries

- Treat `README.md` as the public project ledger. Keep it current when setup, deployment, environment variables, roles, backup/restore, limitations, or operational behavior change.
- Treat `implementation-documentation/` as private planning material. It can guide implementation, but do not leak its paths, file names, headings, planning-only labels, or unfinished notes into public docs, UI copy, routes, source identifiers, tests, generated artifacts, or configuration names.
- If private planning notes and tracked project files conflict, prefer the tracked project files unless the owner explicitly says otherwise.

## Product direction

- Build a real shared-calendar web app for events such as kayaking, birthdays, trips, and friend-group plans.
- Support multiple calendars in v1. Registered users can create their own calendars.
- The creator of a calendar is that calendar's `ADMIN`.
- Calendar admins can invite `EDITOR` and `VIEWER` members.
- Calendars are public by default through long, random, unguessable links. Public links are read-only and should be marked `noindex`.
- Keep the app as one server-rendered Jakarta EE application: browser, Open Liberty, Jakarta Faces / JSF, PrimeFaces, CDI / EJB Lite services, JPA, PostgreSQL, and Flyway.
- Use Java 25 as the runtime target, Maven as the build tool, WAR packaging, Docker for production packaging, and Railway as the first deployment target.
- Keep the app CLI-first and reproducible. Required workflows must be available through committed scripts, Maven Wrapper, Docker Compose, or `mise` tasks; IntelliJ must not be required.
- Keep the UI modern, flat, sleek, and practical.

## Stack boundaries

- Use Jakarta-era APIs. Do not introduce `javax.*` enterprise imports or old JSF XML namespaces. `javax.sql.DataSource` is acceptable when needed because it is part of Java SE.
- Use the PrimeFaces `jakarta` classifier.
- Keep JPA provider-neutral. Do not add Hibernate-specific code unless the owner explicitly asks.
- Do not replace the stack with Spring Boot, a JavaScript frontend framework, a REST-first architecture, GraphQL, Netlify-hosted UI, Kubernetes, Keycloak, OAuth, SSO, or external identity providers unless explicitly requested.
- Do not add recurring events, ICS import/export, email reminders, push notifications, mobile apps, complex privacy modes beyond public-link enable/disable, or horizontal scaling to v1 unless explicitly requested.

## Security and authorization

- Never store or log plaintext passwords.
- Do not log public calendar tokens or invite tokens. Treat them as bearer secrets.
- UI controls may hide unavailable actions, but service methods must still enforce roles.
- Preserve the calendar role model: `VIEWER`, `EDITOR`, and `ADMIN`.
- Treat `VIEWER`, `EDITOR`, and `ADMIN` as calendar-scoped roles, not global application roles.
- Protect the last active `ADMIN` membership on every active calendar from being disabled or demoted.
- Keep production cookies secure and HTTP-only, and do not rely on client-side checks for authorization.
- Public calendar access must be read-only, unguessable, and marked `noindex`.
- Invite links must be revocable and must assign only the intended calendar role.

## Implementation rules

- Preserve existing behavior unless the task asks for a change.
- Fix root causes. Do not hide, silence, swallow, or work around errors without understanding the cause.
- Prefer boring, direct code over speculative abstractions.
- Organize Java packages by feature, not by generic layers when feature locality is clearer.
- Backing beans coordinate UI only. Services own business rules. Migrations own schema.
- Keep role names, password hashing parameters, token generation, and environment variable names centralized.
- Use full, readable names for variables, methods, classes, files, tests, routes, and UI copy.
- Use sentence-case headings and UI copy.
- Use kebab-case for non-component files where practical.

## Repository hygiene

- Do not commit `.env`, downloaded toolchains, Maven distributions, PostgreSQL driver jars, IDE state, `target/`, generated build output, backups, or local scratch artifacts.
- Do not edit generated output directly unless the task explicitly targets generated artifacts.
- Do not add shell wrappers for non-trivial orchestration. Keep shell scripts thin and move real orchestration into portable project code when needed.
- Never rely on platform-specific project scripts for required workflows. Prefer portable `mise` tasks, Maven configuration, Java source-launcher helpers, or Docker Compose commands that work the same way across supported platforms.
- Use `rg` for content search and `rg --files` for file search. Avoid broad unignored searches unless there is a specific reason.

## Testing and verification

- Prefer focused tests for application logic: password policy, registration, calendar creation, token generation, invite acceptance, event validation, role checks, last-admin protection, and time handling.
- Tests should cover edge cases and failure paths, not only obvious equality checks.
- Do not accept flaky tests. Reproduce, identify the root cause, and fix it.
- For code changes, use the repository's `mise` tasks. Expected checks are:

```bash
mise run package
mise run db
mise run dev
mise run verify-local
```

Run `mise run dev` in a separate terminal when another verification command needs the application to stay online. If production Docker packaging changes or a Dockerfile is present, also run the relevant Docker build.

- For documentation-only changes, narrow review is enough unless the documentation changes commands that should be verified.

## Completion standard

- A task is done only when the relevant code, tests, documentation, and local verification are complete.
- For app features, verify the behavior through the smallest reliable automated or manual path that exercises the real application behavior.
- Before claiming deployment or production readiness, verify Docker runtime behavior, database persistence, registration, calendar role behavior, public links, invite links, and backup/restore instructions.
