# M1: persistence and security core

Use this milestone to implement schema migrations, persistence, authentication, authorization, bootstrap admin setup, domain services, password handling, audit foundation, and focused tests.

## Milestone checklist


Outcome: database schema, JPA domain, authentication, bootstrap admin, role checks, password hashing, and audit foundation are implemented with focused tests.

Tasks:

1. Add Flyway migrations and a startup migration bean.
2. Add `persistence.xml` and provider-neutral JPA entities.
3. Add event, user, role, password, and audit services.
4. Add bootstrap admin creation from environment variables.
5. Add Jakarta security configuration, web security constraints, login view logic, current-user helper, and logout support.
6. Add unit tests for password policy, event validation, role helpers, last-admin protection, and time handling.

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

1. App starts with no users and bootstrap environment variables.
2. Admin user exists after startup.
3. Login works.
4. `/app/calendar.xhtml` is inaccessible when logged out.
5. Logout works.
6. Wrong password shows a generic failure.

Acceptance criteria:

1. Tables and Flyway schema history exist.
2. Restarting the app does not re-run migrations incorrectly.
3. Entities map to the migration schema.
4. No Hibernate-specific imports are used.
5. `@Version` is used for event optimistic locking.
6. No plaintext passwords are stored or logged.
7. Bootstrap is ignored once users exist.
8. Roles load from `app_user_role`.
9. Service methods enforce roles, not only UI controls.



## Implementation details

## 8. Persistence configuration

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
3. Keep entity code provider-neutral. Avoid Hibernate-specific annotations.

---


## 9. Database schema

Create `src/main/resources/db/migration/V1__initial_schema.sql`.

Use this initial schema:

```sql
create table app_user (
    id bigserial primary key,
    username varchar(80) not null unique,
    display_name varchar(160) not null,
    password_hash text not null,
    active boolean not null default true,
    must_change_password boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table app_user_role (
    user_id bigint not null references app_user(id) on delete cascade,
    role_name varchar(20) not null,
    primary key (user_id, role_name),
    check (role_name in ('VIEWER', 'EDITOR', 'ADMIN'))
);

create index idx_app_user_role_role_name
    on app_user_role(role_name);

create table calendar_event (
    id bigserial primary key,
    title varchar(200) not null,
    description text,
    location varchar(200),
    start_at timestamptz not null,
    end_at timestamptz not null,
    all_day boolean not null default false,
    created_by_user_id bigint not null references app_user(id),
    updated_by_user_id bigint references app_user(id),
    version integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (length(trim(title)) > 0),
    check (end_at > start_at)
);

create index idx_calendar_event_start_at
    on calendar_event(start_at);

create index idx_calendar_event_end_at
    on calendar_event(end_at);

create index idx_calendar_event_range
    on calendar_event(start_at, end_at);

create table audit_log (
    id bigserial primary key,
    actor_user_id bigint references app_user(id),
    entity_type varchar(80) not null,
    entity_id bigint,
    action varchar(80) not null,
    details text,
    created_at timestamptz not null default now()
);

create index idx_audit_log_created_at
    on audit_log(created_at);

create index idx_audit_log_entity
    on audit_log(entity_type, entity_id);
```

Do not add recurring-event tables in v1.

---


## 10. Startup sequence

Create two startup beans:

```text
startup/DatabaseMigration.java
startup/BootstrapAdmin.java
```

### 10.1 Database migration bean

`DatabaseMigration` responsibilities:

1. Run Flyway migrations during application startup.
2. Fail startup if migrations fail.
3. Log the applied migration version.

Implementation shape:

```java
package com.example.calendar.startup;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

@Singleton(name = "DatabaseMigration")
@Startup
public class DatabaseMigration {

    @Resource(lookup = "jdbc/CalendarDS")
    private DataSource dataSource;

    @PostConstruct
    public void migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .load()
            .migrate();
    }
}
```

`javax.sql.DataSource` is correct here because it is part of Java SE, not old Java EE. Do not replace it with `jakarta.sql.DataSource`; that type does not exist.

### 10.2 Bootstrap admin bean

`BootstrapAdmin` responsibilities:

1. Run after migrations.
2. Check if any users exist.
3. If no users exist, read:
   - `APP_BOOTSTRAP_ADMIN_USERNAME`
   - `APP_BOOTSTRAP_ADMIN_PASSWORD`
4. Create first admin user with roles `ADMIN`, `EDITOR`, `VIEWER`.
5. Set `must_change_password=true`.
6. Log that bootstrap happened, without logging the password.
7. If no users exist and bootstrap variables are missing, fail startup with a clear message.
8. If users already exist, ignore bootstrap variables.

Use `@DependsOn("DatabaseMigration")`.

---



## 11. Authentication and authorization

### 11.1 Chosen login model

Use **custom JSF login backed by Jakarta Security**.

This is still a Jakarta Security pattern, but it avoids brittle `j_security_check` form wiring and keeps the UI readable. Do not mix this with container-form-login conventions. In particular:

1. Do not post to `j_security_check`.
2. Do not depend on fields named `j_username` and `j_password`.
3. Do not add a second login mechanism later.

### 11.2 Security configuration

Create `config/SecurityConfig.java`:

```java
package com.example.calendar.config;

import jakarta.annotation.security.DeclareRoles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.annotation.FacesConfig;
import jakarta.security.enterprise.authentication.mechanism.http.CustomFormAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.LoginToContinue;
import jakarta.security.enterprise.identitystore.DatabaseIdentityStoreDefinition;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;

@ApplicationScoped
@FacesConfig
@DeclareRoles({"VIEWER", "EDITOR", "ADMIN"})
@CustomFormAuthenticationMechanismDefinition(
    loginToContinue = @LoginToContinue(
        loginPage = "/login.xhtml",
        errorPage = "/login-error.xhtml"
    )
)
@DatabaseIdentityStoreDefinition(
    dataSourceLookup = "jdbc/CalendarDS",
    callerQuery = "select password_hash from app_user where username = ? and active = true",
    groupsQuery = "select r.role_name " +
                  "from app_user_role r " +
                  "join app_user u on u.id = r.user_id " +
                  "where u.username = ? and u.active = true",
    hashAlgorithm = Pbkdf2PasswordHash.class,
    hashAlgorithmParameters = {
        "Pbkdf2PasswordHash.Algorithm=PBKDF2WithHmacSHA512",
        "Pbkdf2PasswordHash.Iterations=210000",
        "Pbkdf2PasswordHash.SaltSizeBytes=32",
        "Pbkdf2PasswordHash.KeySizeBytes=32"
    }
)
public class SecurityConfig {
}
```

### 11.3 Web security constraints

Create `src/main/webapp/WEB-INF/web.xml`:

```xml
<web-app version="6.0"
         xmlns="https://jakarta.ee/xml/ns/jakartaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd">

    <display-name>Shared calendar</display-name>

    <welcome-file-list>
        <welcome-file>index.xhtml</welcome-file>
    </welcome-file-list>

    <security-constraint>
        <web-resource-collection>
            <web-resource-name>Authenticated app</web-resource-name>
            <url-pattern>/app/*</url-pattern>
        </web-resource-collection>
        <auth-constraint>
            <role-name>VIEWER</role-name>
            <role-name>EDITOR</role-name>
            <role-name>ADMIN</role-name>
        </auth-constraint>
    </security-constraint>

    <security-constraint>
        <web-resource-collection>
            <web-resource-name>Admin area</web-resource-name>
            <url-pattern>/app/admin/*</url-pattern>
        </web-resource-collection>
        <auth-constraint>
            <role-name>ADMIN</role-name>
        </auth-constraint>
    </security-constraint>

    <security-role>
        <role-name>VIEWER</role-name>
    </security-role>
    <security-role>
        <role-name>EDITOR</role-name>
    </security-role>
    <security-role>
        <role-name>ADMIN</role-name>
    </security-role>
</web-app>
```

### 11.4 Login view bean

Create `security/LoginView.java`.

Responsibilities:

1. Receive username/password from `login.xhtml`.
2. Trim username.
3. Call `SecurityContext.authenticate`.
4. Redirect to `/app/calendar.xhtml` on success.
5. Show generic error on failure.
6. Never reveal whether username or password was wrong.

Implementation shape:

```java
package com.example.calendar.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.security.enterprise.credential.Password;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Named
@RequestScoped
public class LoginView {

    @Inject
    private SecurityContext securityContext;

    @Inject
    private FacesContext facesContext;

    private String username;
    private String password;

    public String login() {
        HttpServletRequest request =
            (HttpServletRequest) facesContext.getExternalContext().getRequest();
        HttpServletResponse response =
            (HttpServletResponse) facesContext.getExternalContext().getResponse();

        AuthenticationStatus status = securityContext.authenticate(
            request,
            response,
            AuthenticationParameters.withParams()
                .credential(new UsernamePasswordCredential(username, new Password(password)))
        );

        if (status == AuthenticationStatus.SUCCESS) {
            return "/app/calendar.xhtml?faces-redirect=true";
        }

        facesContext.addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_ERROR,
                "Invalid username or password.", null));

        return null;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
```

### 11.5 Current user helper

Create `security/CurrentUser.java`:

```java
package com.example.calendar.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

@Named
@RequestScoped
public class CurrentUser {

    @Inject
    private SecurityContext securityContext;

    @Inject
    private FacesContext facesContext;

    public String getUsername() {
        Principal principal = securityContext.getCallerPrincipal();
        return principal == null ? null : principal.getName();
    }

    public boolean isViewer() {
        return securityContext.isCallerInRole("VIEWER")
            || securityContext.isCallerInRole("EDITOR")
            || securityContext.isCallerInRole("ADMIN");
    }

    public boolean isEditor() {
        return securityContext.isCallerInRole("EDITOR")
            || securityContext.isCallerInRole("ADMIN");
    }

    public boolean isAdmin() {
        return securityContext.isCallerInRole("ADMIN");
    }

    public String logout() throws Exception {
        HttpServletRequest request =
            (HttpServletRequest) facesContext.getExternalContext().getRequest();
        request.logout();
        request.getSession().invalidate();
        return "/login.xhtml?faces-redirect=true";
    }
}
```

---


## 12. Domain model

### 12.1 Entities

Create these JPA entities:

```text
event/CalendarEvent.java
user/AppUser.java
user/AppUserRole.java
audit/AuditLog.java
```

Use `@Version` on `CalendarEvent.version` for optimistic locking.

Use `OffsetDateTime` for timestamps unless the agent has tested `Instant` mapping successfully with the chosen Liberty/EclipseLink version.

Example field style:

```java
@Column(name = "start_at", nullable = false)
private OffsetDateTime startAt;

@Column(name = "end_at", nullable = false)
private OffsetDateTime endAt;

@Version
@Column(nullable = false)
private int version;
```

Entity rules:

1. Keep entities persistence-focused.
2. Put UI form state in DTOs, not directly in entities.
3. Do not expose mutable entity collections to JSF pages unless necessary.
4. Validate business rules in services, not only in database constraints.

### 12.2 DTOs and command records

Create small DTOs/records:

```text
event/CalendarEventForm.java
event/CreateCalendarEventCommand.java
event/UpdateCalendarEventCommand.java
user/UserForm.java
user/CreateUserCommand.java
user/UpdateUserRolesCommand.java
```

Use Java records for immutable command inputs where convenient:

```java
public record CreateCalendarEventCommand(
    String title,
    String description,
    String location,
    OffsetDateTime startAt,
    OffsetDateTime endAt,
    boolean allDay
) {}
```

---


## 13. Service layer

Use EJB Lite `@Stateless` for services that need method-level role enforcement.

Create:

```text
event/CalendarService.java
user/UserService.java
audit/AuditService.java
security/PasswordService.java
```

### 13.1 CalendarService

Responsibilities:

1. Find events in date range.
2. Create event.
3. Update event.
4. Delete event.
5. Validate title, start, end, role permissions.
6. Write audit logs.

Method security:

```java
@RolesAllowed({"VIEWER", "EDITOR", "ADMIN"})
public List<CalendarEvent> findEvents(OffsetDateTime from, OffsetDateTime to) { ... }

@RolesAllowed({"EDITOR", "ADMIN"})
public CalendarEvent createEvent(CreateCalendarEventCommand command) { ... }

@RolesAllowed({"EDITOR", "ADMIN"})
public CalendarEvent updateEvent(UpdateCalendarEventCommand command) { ... }

@RolesAllowed({"EDITOR", "ADMIN"})
public void deleteEvent(long eventId) { ... }
```

Deletion rule for v1:

1. `ADMIN` may delete any event.
2. `EDITOR` may delete any event in v1 unless you decide to enforce owner-only editing.
3. If owner-only editing is desired later, add that as v1.1, not in the first pass.

### 13.2 UserService

Responsibilities:

1. Find users.
2. Create users.
3. Disable users.
4. Reset passwords.
5. Assign roles.
6. Prevent the last admin from being disabled or stripped of `ADMIN`.
7. Prevent an admin from accidentally disabling themselves without another admin existing.
8. Write audit logs.

Method security:

```java
@RolesAllowed("ADMIN")
public List<AppUser> findUsers() { ... }

@RolesAllowed("ADMIN")
public AppUser createUser(CreateUserCommand command) { ... }

@RolesAllowed("ADMIN")
public void updateRoles(UpdateUserRolesCommand command) { ... }

@RolesAllowed("ADMIN")
public void disableUser(long userId) { ... }
```

### 13.3 PasswordService

Responsibilities:

1. Hash passwords with the same parameters used by `DatabaseIdentityStoreDefinition`.
2. Validate password policy before hashing.
3. Provide bootstrap/admin reset support.

Minimum password policy for this personal app:

```text
length >= 14
not blank
not equal to username
```

Do not implement complex composition rules. Length and non-reuse matter more.

---



