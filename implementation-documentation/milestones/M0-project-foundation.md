# M0: project foundation

Use this milestone to create the runnable skeleton, project tooling, Dockerized local PostgreSQL, Open Liberty configuration, placeholder JSF/PrimeFaces pages, a modern flat app shell, and the health endpoint.

M0 must not implement persistence, registration, login, invitations, calendar membership, or event storage. It should only prove that the chosen stack starts cleanly and that the visible shell points toward the intended product.

## Milestone checklist

Outcome: a CLI-first Jakarta EE web app that builds, starts locally, proves JSF/PrimeFaces rendering, and shows placeholders for public calendars, self-registration, owned calendars, invite-based membership, and event pages.

Tasks:

1. Create the repository file tree.
2. Create `.gitignore`, `.editorconfig`, `.env.example`, `README.md`, `pom.xml`, `docker-compose.yml`, and Open Liberty `server.xml`.
3. Create `.mise.toml`, `.java-version`, Maven wrapper files, and the portable Java source-launcher helper.
4. Configure Docker Compose with `postgres:17` for the local PostgreSQL service.
5. Create placeholder XHTML pages, a shared template, app CSS, and a `HealthServlet`.
6. Add minimal PrimeFaces components to prove the Jakarta classifier and assets are loading.
7. Make the shell visually aligned with a modern, flat, sleek calendar app.
8. Show placeholder navigation for public calendar links, registration, signed-in calendar list, calendar detail, and calendar member management.
9. Add the initial GitHub Actions PR check for the Maven wrapper build.

Verification:

```bash
mise run setup
./mvnw -version
./mvnw dependency:tree | grep primefaces
./mvnw dependency:tree | grep jakarta
./mvnw clean test package
mise run db
mise run dev
curl -i http://localhost:9080/health
docker compose exec postgres psql -U calendar -d calendar -c 'select current_database(), current_user;'
```

GitHub PR check:

```bash
./mvnw -B -ntp clean test package
./mvnw -B -ntp dependency:tree "-Dincludes=org.primefaces:primefaces"
```

Manual checks:

1. Open `http://localhost:9080/`.
2. Confirm the page renders.
3. Confirm PrimeFaces CSS and JavaScript load.
4. Open the public calendar placeholder.
5. Open the registration placeholder.
6. Open the authenticated workspace placeholders.
7. Confirm the UI uses a clean flat layout and remains usable on narrow screens.

Acceptance criteria:

1. WAR builds.
2. `/health` returns `200 ok`.
3. Local PostgreSQL runs through Docker Compose.
4. No host PostgreSQL server or host `psql` installation is required.
5. No `javax.*` enterprise imports exist except `javax.sql.DataSource` when needed.
6. PrimeFaces dependency output shows the `jakarta` classifier.
7. Placeholder copy does not promise implemented registration, login, persistence, invites, or event storage before M1/M2.
8. Public docs describe the current foundation accurately.
9. GitHub PR checks run on pull requests and pushes to `master`, use least-privilege read permissions, and do not require secrets or deployment access.
10. Browser-facing routes are extensionless; `.xhtml` remains the Facelets file suffix rather than the canonical URL shape.

## Repository setup

Create this structure for M0:

```text
.
|-- .dockerignore
|-- .editorconfig
|-- .env.example
|-- .gitignore
|-- README.md
|-- docker-compose.yml
|-- pom.xml
|-- .mise.toml
|-- .java-version
|-- .mvn
|   `-- wrapper
|       `-- maven-wrapper.properties
|-- .github
|   `-- workflows
|       `-- pr-checks.yml
|-- mvnw
|-- mvnw.cmd
|-- scripts
|   `-- calendar-tool.java
`-- src
    `-- main
        |-- java
        |   `-- app
        |       |-- config
        |       `-- health
        |-- liberty
        |   `-- config
        |       `-- server.xml
        `-- webapp
            |-- index.xhtml
            |-- login.xhtml
            |-- login-error.xhtml
            |-- register.xhtml
            |-- public-calendar.xhtml
            |-- app
            |   |-- calendars.xhtml
            |   |-- calendar.xhtml
            |   `-- calendar-members.xhtml
            |-- resources
            |   `-- css
            |       `-- app.css
            `-- WEB-INF
                |-- beans.xml
                |-- templates
                |   `-- main.xhtml
                `-- web.xml
```

Do not create Flyway migrations, JPA entities, login services, registration services, or calendar services in M0.

## GitHub PR checks

M0 should add one required, fast PR check:

1. Run on `pull_request` and pushes to `master`.
2. Use `actions/checkout` and `actions/setup-java` with Temurin Java 25 and Maven cache.
3. Build through the committed Maven wrapper with `./mvnw -B -ntp clean test package`.
4. Verify the PrimeFaces dependency tree shows the `jakarta` classifier.
5. Set workflow permissions to `contents: read`.
6. Do not use secrets, deployment credentials, Railway integration, or write permissions on PRs.

Keep this check small enough to run on every PR. Database integration, app smoke, Docker build, Dependency Review, and CodeQL checks are added in later milestones when they have real behavior to verify.

## Maven plan

Create `pom.xml` with pinned versions:

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
    <jakartaee.version>10.0.0</jakartaee.version>
    <primefaces.version>15.0.16</primefaces.version>
    <postgresql.version>42.7.13</postgresql.version>
    <flyway.version>12.10.0</flyway.version>
    <junit.version>5.11.4</junit.version>
    <liberty.maven.plugin.version>3.12.0</liberty.maven.plugin.version>
</properties>
```

Dependencies:

1. `jakarta.jakartaee-web-api` with provided scope.
2. `org.primefaces:primefaces` with classifier `jakarta`.
3. PostgreSQL driver.
4. Flyway core and PostgreSQL support for later milestones.
5. JUnit 5 for later focused tests.

The PrimeFaces dependency output must show the `jakarta` classifier. If it does not, stop and fix that before writing more code.

## Local development environment

The developer machine should have:

```text
mise CLI as the bootstrap tool manager
Java 25 JDK installed by mise from repository config
Maven downloaded by Maven wrapper, not globally installed
Docker Desktop or Docker Engine
Git
No host PostgreSQL installation required
```

Local PostgreSQL is infrastructure, not a host prerequisite. Run it through Docker Compose and use Dockerized client commands for inspection, backup, and restore.

Create `.env.example` with local defaults:

```bash
PORT=9080
COOKIE_SECURE=false
PGHOST=localhost
PGPORT=5432
PGDATABASE=calendar
PGUSER=calendar
PGPASSWORD=calendar
APP_TIMEZONE=Europe/Warsaw
APP_BASE_URL=http://localhost:9080
APP_REGISTRATION_ENABLED=true
```

## Open Liberty configuration

Use `webProfile-10.0` first. It ensures the needed Jakarta EE 10 web features are available.

`server.xml` must:

1. Bind the endpoint to `host="*"`.
2. Use the `${PORT}` variable.
3. Keep local `COOKIE_SECURE=false`.
4. Configure `cookieHttpOnly="true"`, `cookieSameSite="Lax"`, and `urlRewritingEnabled="false"`.
5. Define `jdbc/CalendarDS` using the PostgreSQL driver copied into Liberty config resources.
6. Deploy `shared-calendar.war` at context root `/`.

The PostgreSQL JDBC driver must be visible to Liberty as a server resource, not only as a WAR dependency. The local setup task copies it into generated Liberty shared resources under `target/`; keep copied jars out of Git.

## Placeholder pages

M0 pages should communicate product direction without pretending later workflows already work.

1. `index.xhtml`: app-first overview with a public calendar preview and links to registration and the calendar workspace.
2. `public-calendar.xhtml`: read-only public calendar placeholder showing the long-link model.
3. `register.xhtml`: disabled registration form placeholder that explains registration is added in M1.
4. `login.xhtml`: disabled sign-in form placeholder that explains sign-in is added in M1.
5. `app/calendars.xhtml`: signed-in calendar list placeholder.
6. `app/calendar.xhtml`: calendar detail/event placeholder.
7. `app/calendar-members.xhtml`: invite/member placeholder.

Use PrimeFaces components on these pages so PrimeFaces CSS and JavaScript are loaded.

Enable Jakarta Faces automatic extensionless mapping in `web.xml` and use extensionless links in rendered UI and browser tests. For example, users should see `/login`, `/register`, `/public-calendar`, `/app/calendars`, `/app/calendar`, and `/app/calendar-members` rather than `.xhtml` URLs.

## Health endpoint

Create `health/HealthServlet.java`:

```java
package app.health;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/health")
public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/plain; charset=UTF-8");
        response.getWriter().write("ok");
    }
}
```

Do not require database access for `/health`. This is a liveness check.
