# M1: persistence and security core

Use this milestone to implement schema migrations, JPA persistence, self-registration, login, password handling, calendar creation, calendar membership authorization, public calendar tokens, invite tokens, audit foundation, and focused tests.

Do not build the full PrimeFaces calendar workflow in M1. M1 should establish the data model and service rules that M2 can safely expose.

## Milestone checklist

Outcome: users can register, log in, create calendars, receive calendar `ADMIN` membership, and rely on tested service-layer authorization for public viewing, member roles, invite acceptance, and event validation.

Tasks:

1. Add Flyway migrations and a startup migration bean.
2. Add `persistence.xml` and provider-neutral JPA entities.
3. Add user, password, registration, calendar, membership, invitation, event, and audit services.
4. Add Jakarta Security configuration, web security constraints, login view logic, registration view logic, current-user helper, and logout support.
5. Generate public calendar tokens and invite tokens with UUID v4 or stronger random values.
6. Add focused tests for password policy, registration validation, calendar creation, role checks, invite acceptance, last-admin protection, event validation, token generation, and time handling.
7. Extend CI with a PostgreSQL-backed job once migrations and database-backed tests exist.

Verification:

```bash
./mvnw clean test package
mise run db
mise run dev
docker compose exec postgres psql -U calendar -d calendar -c '\dt'
docker compose exec postgres psql -U calendar -d calendar -c 'select * from flyway_schema_history order by installed_rank;'
curl -i http://localhost:9080/health
```

Manual checks:

1. App starts with an empty database.
2. A new user can register.
3. Login works after registration.
4. A registered user can create a calendar.
5. The calendar creator receives `ADMIN` membership.
6. A public token is generated for the calendar.
7. Logout works.
8. Wrong password shows a generic failure.

Acceptance criteria:

1. Tables and Flyway schema history exist.
2. Restarting the app does not re-run migrations incorrectly.
3. Entities map to the migration schema.
4. No Hibernate-specific imports are used.
5. `@Version` is used for event optimistic locking.
6. No plaintext passwords are stored or logged.
7. No public calendar tokens or invite tokens are logged.
8. Registration validates username and password policy.
9. Calendar creation grants exactly one initial `ADMIN` membership to the creator.
10. Roles load from `calendar_member`.
11. Service methods enforce calendar membership and role checks, not only UI controls.
12. PostgreSQL-backed CI fails on migration, persistence, or service authorization regressions without using repository secrets.

## Persistence configuration

Create `src/main/resources/META-INF/persistence.xml`:

```xml
<persistence version="3.1"
             xmlns="https://jakarta.ee/xml/ns/persistence"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence https://jakarta.ee/xml/ns/persistence/persistence_3_1.xsd">

    <persistence-unit name="calendarPU" transaction-type="JTA">
        <jta-data-source>jdbc/CalendarDS</jta-data-source>

        <properties>
            <property name="jakarta.persistence.schema-generation.database.action" value="none"/>
            <property name="eclipselink.logging.level" value="INFO"/>
        </properties>
    </persistence-unit>

</persistence>
```

Rules:

1. Flyway owns schema changes.
2. JPA must not auto-create, update, or drop schema in production.
3. Keep entity code provider-neutral.

## Database schema

Create `src/main/resources/db/migration/V1__initial_schema.sql`.

Use this initial schema:

```sql
create table app_user (
    id bigserial primary key,
    username varchar(80) not null unique,
    display_name varchar(160) not null,
    password_hash text not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (length(trim(username)) > 0),
    check (length(trim(display_name)) > 0)
);

create table calendar (
    id bigserial primary key,
    name varchar(160) not null,
    description text,
    public_token varchar(80) not null unique,
    timezone varchar(80) not null default 'Europe/Warsaw',
    public_access_enabled boolean not null default true,
    active boolean not null default true,
    created_by_user_id bigint not null references app_user(id),
    version integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (length(trim(name)) > 0),
    check (length(trim(public_token)) >= 36)
);

create index idx_calendar_created_by_user_id
    on calendar(created_by_user_id);

create table calendar_member (
    calendar_id bigint not null references calendar(id) on delete cascade,
    user_id bigint not null references app_user(id) on delete cascade,
    role_name varchar(20) not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (calendar_id, user_id),
    check (role_name in ('VIEWER', 'EDITOR', 'ADMIN'))
);

create index idx_calendar_member_user_id
    on calendar_member(user_id);

create index idx_calendar_member_role_name
    on calendar_member(role_name);

create table calendar_invitation (
    id bigserial primary key,
    calendar_id bigint not null references calendar(id) on delete cascade,
    invite_token varchar(80) not null unique,
    role_name varchar(20) not null,
    created_by_user_id bigint not null references app_user(id),
    accepted_by_user_id bigint references app_user(id),
    revoked_at timestamptz,
    accepted_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    check (role_name in ('VIEWER', 'EDITOR')),
    check (length(trim(invite_token)) >= 36)
);

create index idx_calendar_invitation_calendar_id
    on calendar_invitation(calendar_id);

create table calendar_event (
    id bigserial primary key,
    calendar_id bigint not null references calendar(id) on delete cascade,
    title varchar(200) not null,
    description text,
    location varchar(200),
    start_at timestamptz not null,
    end_at timestamptz not null,
    all_day boolean not null default false,
    created_by_user_id bigint references app_user(id),
    updated_by_user_id bigint references app_user(id),
    version integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (length(trim(title)) > 0),
    check (end_at > start_at)
);

create index idx_calendar_event_calendar_start
    on calendar_event(calendar_id, start_at);

create index idx_calendar_event_calendar_end
    on calendar_event(calendar_id, end_at);

create table audit_log (
    id bigserial primary key,
    actor_user_id bigint references app_user(id),
    calendar_id bigint references calendar(id),
    entity_type varchar(80) not null,
    entity_id bigint,
    action varchar(80) not null,
    details text,
    created_at timestamptz not null default now()
);

create index idx_audit_log_created_at
    on audit_log(created_at);

create index idx_audit_log_calendar_id
    on audit_log(calendar_id);

create index idx_audit_log_entity
    on audit_log(entity_type, entity_id);
```

Do not add recurring-event tables in v1.

## Startup sequence

Create one startup bean:

```text
startup/DatabaseMigration.java
```

`DatabaseMigration` responsibilities:

1. Run Flyway migrations during application startup.
2. Fail startup if migrations fail.
3. Log the applied migration version without logging database credentials.

Use `javax.sql.DataSource` for the injected data source because it is part of Java SE, not old Java EE.

Do not create a first-user application administrator in this product model. Registered users create their own calendars and become calendar admins for those calendars.

## Authentication and registration

Use custom JSF login backed by Jakarta Security. Do not post to `j_security_check`.

Security configuration should declare an authenticated application role such as `USER`, while calendar roles are enforced by application services from `calendar_member`.

The database identity store can return a constant `USER` group for active registered users. Calendar `VIEWER`, `EDITOR`, and `ADMIN` roles must not be treated as global app roles.

Registration responsibilities:

1. Accept username, display name, password, and initial calendar name.
2. Validate username and display name are not blank.
3. Validate password policy before hashing.
4. Create the user.
5. Create the first calendar unless the user explicitly chooses to do that later.
6. Generate the calendar public token.
7. Grant the creator calendar `ADMIN`.
8. Log account and calendar creation without logging password or tokens.
9. Sign the user in or redirect to login after success.

Minimum password policy:

```text
length >= 14
not blank
not equal to username
```

Do not implement complex composition rules.

## Domain model

Create these JPA entities:

```text
audit/AuditLog.java
calendar/Calendar.java
event/CalendarEvent.java
invitation/CalendarInvitation.java
membership/CalendarMember.java
user/AppUser.java
```

Use `@Version` on `Calendar.version` and `CalendarEvent.version`.

Use `OffsetDateTime` for timestamps unless `Instant` mapping has been tested successfully with the chosen Liberty/EclipseLink version.

Entity rules:

1. Keep entities persistence-focused.
2. Put UI form state in DTOs, not directly in entities.
3. Validate business rules in services, not only in database constraints.
4. Do not expose public tokens or invite tokens in logs or audit details.

## Service layer

Use EJB Lite `@Stateless` for services that need transaction boundaries and method-level authenticated-user checks.

Create:

```text
audit/AuditService.java
calendar/CalendarService.java
event/CalendarEventService.java
invitation/InvitationService.java
membership/CalendarAccessService.java
membership/CalendarMembershipService.java
security/PasswordService.java
security/TokenService.java
user/RegistrationService.java
user/UserService.java
```

### CalendarService

Responsibilities:

1. Create calendar.
2. Generate public token.
3. Find calendar by public token.
4. Find calendars for signed-in user.
5. Update calendar settings.
6. Rotate public token.
7. Write audit logs.

Rules:

1. Any active registered user may create calendars.
2. The creator receives active `ADMIN` membership.
3. Public token must be generated by `TokenService`.
4. Public token must not be derived from calendar id or name.

### CalendarAccessService

Responsibilities:

1. Check whether a user can view, edit, or administer a calendar.
2. Distinguish public-token read access from authenticated member access.
3. Reject mutations for public visitors and viewers.
4. Enforce last-admin protection.

### InvitationService

Responsibilities:

1. Create viewer/editor invite links for calendar admins.
2. Revoke invite links for calendar admins.
3. Accept invite links for signed-in users.
4. Accept invite links after registration.
5. Reject expired, revoked, already accepted, or invalid invites.
6. Write audit logs.

Do not send email in v1. The UI should show copyable invite links.

### CalendarEventService

Responsibilities:

1. Find events by public token and date range.
2. Find events by member calendar and date range.
3. Create event.
4. Update event.
5. Delete event.
6. Validate title, start, end, and role permissions.
7. Write audit logs.

Rules:

1. Public token users may read only.
2. `VIEWER` members may read only.
3. `EDITOR` members may create, edit, and delete events.
4. `ADMIN` members may create, edit, and delete events.

### CalendarMembershipService

Responsibilities:

1. List members for calendar admins.
2. Change member roles for calendar admins.
3. Disable member access for calendar admins.
4. Prevent the final active calendar admin from being disabled or demoted.
5. Write audit logs.

## Focused tests

Add JUnit 5 tests for:

1. Password policy edge cases.
2. Registration rejects duplicate and blank usernames.
3. Calendar creation grants creator `ADMIN`.
4. Token generation returns unique non-sequential values.
5. Public read access cannot mutate.
6. Viewer cannot mutate.
7. Editor can mutate events but cannot manage members.
8. Admin can manage members.
9. Last-admin protection rejects demotion and removal.
10. Invite acceptance assigns the intended role.
11. Revoked, expired, and reused invites are rejected.
12. Event blank title and end-before-start are rejected.

## GitHub PR checks after M1

Keep the M0 Maven build check required. Add a database-backed CI job after the Flyway migrations and persistence tests exist.

The database job should:

1. Run on `pull_request` and pushes to `master`.
2. Use PostgreSQL 17, matching local Docker Compose.
3. Wait for PostgreSQL readiness with a health check, not a fixed sleep.
4. Use test-only database credentials defined in the workflow environment.
5. Run the focused migration, persistence, and service tests through the Maven wrapper.
6. Fail on authorization, password, token, migration, and validation regressions.

Prefer a GitHub Actions PostgreSQL service container for this lane. Use Docker Compose only if the application test command needs the same Compose wiring as local development. Do not make this job required until it is deterministic locally and in CI.
