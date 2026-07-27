# Agent instructions

## Product direction

- Build a small shared-calendar app for events such as birthdays, trips, kayaking, and friend-group plans.
- Keep multiple calendars. A calendar creator is its first `ADMIN`; invited members are `EDITOR`.
- Share read-only access through the calendar's compact bearer URL. Do not add a read-only membership role.
- Treat all-day form dates as inclusive and preserve their civil dates across time-zone changes.
- Keep one server-rendered Jakarta EE application using Open Liberty, Jakarta Faces, PrimeFaces, CDI or EJB Lite, JPA, PostgreSQL, and Flyway.
- Use Java 25, Maven, WAR packaging, Docker, and Railway.
- Keep the UI modern, flat, responsive, and practical.
- Prefer direct code appropriate for a small private app. Do not introduce enterprise-scale policy, history, quotas, compatibility layers, or infrastructure without a concrete need.

## Scope boundaries

- Use Jakarta APIs and the PrimeFaces `jakarta` classifier. `javax.sql.DataSource` is allowed because it is part of Java SE.
- Keep JPA provider-neutral.
- Do not replace the stack with Spring Boot, a JavaScript framework, REST-first architecture, GraphQL, Kubernetes, OAuth, SSO, or an external identity provider unless explicitly requested.
- Do not add recurring events, ICS import or export, notifications, mobile apps, or complex privacy modes to v1.

## Access and security

- Never store or log plaintext passwords, calendar tokens, or invitation tokens.
- Enforce calendar roles in services, not only in the UI.
- Preserve the calendar-scoped `EDITOR` and `ADMIN` roles and protect the last admin.
- Anonymous bearer-link access is read-only and `noindex`. Disabling public access must not change the member URL; regenerating it must invalidate the old URL.
- Invitation links expire after seven days, can grant only `EDITOR`, and become unusable when their creator loses permission. Calendar admins can list and revoke outstanding editor invitations.
- Serialize invitation acceptance so one link can be consumed once, including under concurrent requests.
- Keep bootstrap registration as a one-time database fact claimed in the same transaction as account creation.
- Keep login throttling source-aware and username-aware without exposing whether an account exists.
- Keep authenticated cookies Secure, HTTP-only, and SameSite `Lax`. Password changes must invalidate sessions with the old password version.
- Keep `/health` database-aware.

## Schema policy before launch

- The application has no valuable users or data yet.
- Keep exactly one initial Flyway schema, `V1__initial_schema.sql`.
- When the schema changes before launch, edit `V1` and recreate only this application's verified local and Railway databases. Do not add compatibility migrations or legacy code.
- Never use a broad or ambiguous database deletion command. Verify the project, environment, service, database name, and current connection immediately before a reset.
- After real users are admitted, freeze `V1` and use normal forward migrations.

## Implementation rules

- Fix root causes; do not suppress errors or apply speculative workarounds.
- Prefer boring, direct code and feature-local packages.
- Backing beans coordinate UI. Services own business rules. The initial schema owns persistence structure.
- Use full, readable names and one canonical term for each concept.
- Use sentence case in headings and UI copy. Use kebab-case for non-component files where practical.
- Never use emojis.

## Repository hygiene

- Do not commit `.env`, downloaded tools, Maven distributions, database drivers, IDE state, `.build/`, `.liberty/`, backups, or scratch files.
- Never add or enable Dependabot. Dependency updates are manual unless explicitly changed by the owner.
- Keep orchestration portable through `mise`, Maven, project code, or Docker Compose.
- Keep shell scripts thin.
- Use `rg` and `rg --files` for repository searches. Ignore `temp.txt`.
- Do not use git commands; the owner handles git and GitHub changes.

## Verification

Use the relevant committed tasks:

```text
mise run package
mise run lint-css
mise run lint-workflows
mise run end-to-end
mise run lighthouse
mise run docker-build
```

- Tests must cover meaningful edge and failure cases and must never be accepted as flaky.
- For browser failures, reproduce the behavior, find the root cause, then verify the fix through the real application.
- Update `README.md` when setup, configuration, deployment, access rules, schema policy, backups, limitations, or operational behavior changes.
- Before handoff, leave PostgreSQL and a current-source application instance running. Verify `/health` and a representative page, and report the manual-testing URL.
- Never stop an unrelated process on the default application port. Use non-default ports when needed.
