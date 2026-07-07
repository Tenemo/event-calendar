# M0: project foundation

Use this milestone to create the runnable skeleton, project tooling, Dockerized local PostgreSQL, Open Liberty configuration, placeholder JSF/PrimeFaces pages, and the health endpoint.

## Milestone checklist


Outcome: a CLI-first Jakarta EE web app that builds, starts locally, and proves JSF/PrimeFaces rendering against Dockerized PostgreSQL.

Tasks:

1. Create the repository file tree.
2. Create `.gitignore`, `.editorconfig`, `.env.example`, `README.md`, `pom.xml`, `docker-compose.yml`, and Open Liberty `server.xml`.
3. Create `.mise.toml`, `.java-version`, Maven wrapper files, and thin setup/check scripts.
4. Configure Docker Compose with `postgres:17` for the local PostgreSQL service.
5. Create placeholder XHTML pages, shared templates, app CSS, and a `HealthServlet`.
6. Add a minimal PrimeFaces component to prove the Jakarta classifier and assets are loading.

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

Manual checks:

1. Open `http://localhost:9080/`.
2. Confirm the page renders.
3. Confirm PrimeFaces CSS and JavaScript load.

Acceptance criteria:

1. WAR builds.
2. `/health` returns `200 ok`.
3. Local PostgreSQL runs through Docker Compose.
4. No host PostgreSQL server or host `psql` installation is required.
5. No `javax.*` enterprise imports exist except `javax.sql.DataSource` when needed.
6. PrimeFaces dependency output shows the `jakarta` classifier.



## Implementation details

## 4. Repository setup

The agent should create this structure in the empty repository:

```text
.
â”œâ”€â”€ .dockerignore
â”œâ”€â”€ .editorconfig
â”œâ”€â”€ .env.example
â”œâ”€â”€ .gitignore
â”œâ”€â”€ Dockerfile
â”œâ”€â”€ README.md
â”œâ”€â”€ docker-compose.yml
â”œâ”€â”€ pom.xml
â”œâ”€â”€ scripts
â”‚   â”œâ”€â”€ backup-postgres.sh
â”‚   â”œâ”€â”€ restore-postgres.sh
â”‚   â””â”€â”€ verify-local.sh
â””â”€â”€ src
    â”œâ”€â”€ main
    â”‚   â”œâ”€â”€ java
    â”‚   â”‚   â””â”€â”€ com
    â”‚   â”‚       â””â”€â”€ example
    â”‚   â”‚           â””â”€â”€ calendar
    â”‚   â”‚               â”œâ”€â”€ audit
    â”‚   â”‚               â”œâ”€â”€ config
    â”‚   â”‚               â”œâ”€â”€ event
    â”‚   â”‚               â”œâ”€â”€ health
    â”‚   â”‚               â”œâ”€â”€ security
    â”‚   â”‚               â”œâ”€â”€ startup
    â”‚   â”‚               â”œâ”€â”€ user
    â”‚   â”‚               â””â”€â”€ util
    â”‚   â”œâ”€â”€ liberty
    â”‚   â”‚   â””â”€â”€ config
    â”‚   â”‚       â””â”€â”€ server.xml
    â”‚   â”œâ”€â”€ resources
    â”‚   â”‚   â”œâ”€â”€ META-INF
    â”‚   â”‚   â”‚   â””â”€â”€ persistence.xml
    â”‚   â”‚   â””â”€â”€ db
    â”‚   â”‚       â””â”€â”€ migration
    â”‚   â”‚           â”œâ”€â”€ V1__initial_schema.sql
    â”‚   â”‚           â”œâ”€â”€ V2__seed_audit_indexes.sql
    â”‚   â”‚           â””â”€â”€ V3__user_password_reset_fields.sql
    â”‚   â””â”€â”€ webapp
    â”‚       â”œâ”€â”€ app
    â”‚       â”‚   â”œâ”€â”€ admin
    â”‚       â”‚   â”‚   â””â”€â”€ users.xhtml
    â”‚       â”‚   â””â”€â”€ calendar.xhtml
    â”‚       â”œâ”€â”€ index.xhtml
    â”‚       â”œâ”€â”€ login-error.xhtml
    â”‚       â”œâ”€â”€ login.xhtml
    â”‚       â”œâ”€â”€ resources
    â”‚       â”‚   â””â”€â”€ css
    â”‚       â”‚       â””â”€â”€ app.css
    â”‚       â””â”€â”€ WEB-INF
    â”‚           â”œâ”€â”€ templates
    â”‚           â”‚   â”œâ”€â”€ admin.xhtml
    â”‚           â”‚   â””â”€â”€ main.xhtml
    â”‚           â””â”€â”€ web.xml
    â””â”€â”€ test
        â””â”€â”€ java
            â””â”€â”€ com
                â””â”€â”€ example
                    â””â”€â”€ calendar
```

---


## 5. Maven plan

Create `pom.xml` with pinned versions. The exact patch versions can be updated by the agent after checking Maven Central, but the first implementation should start with the following known-good baseline:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>shared-calendar</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>war</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>

        <jakartaee.version>10.0.0</jakartaee.version>
        <primefaces.version>15.0.16</primefaces.version>
        <postgresql.version>42.7.13</postgresql.version>
        <flyway.version>12.10.0</flyway.version>
        <junit.version>5.11.4</junit.version>
        <liberty.maven.plugin.version>3.12.0</liberty.maven.plugin.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>jakarta.platform</groupId>
            <artifactId>jakarta.jakartaee-web-api</artifactId>
            <version>${jakartaee.version}</version>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>org.primefaces</groupId>
            <artifactId>primefaces</artifactId>
            <version>${primefaces.version}</version>
            <classifier>jakarta</classifier>
        </dependency>

        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>${postgresql.version}</version>
        </dependency>

        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
            <version>${flyway.version}</version>
        </dependency>

        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <version>${flyway.version}</version>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>shared-calendar</finalName>

        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>${maven.compiler.release}</release>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-war-plugin</artifactId>
                <version>3.4.0</version>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
                <configuration>
                    <useModulePath>false</useModulePath>
                </configuration>
            </plugin>

            <plugin>
                <groupId>io.openliberty.tools</groupId>
                <artifactId>liberty-maven-plugin</artifactId>
                <version>${liberty.maven.plugin.version}</version>
            </plugin>
        </plugins>
    </build>
</project>
```

Important Maven checks:

```bash
./mvnw -version
./mvnw dependency:tree | grep primefaces
./mvnw dependency:tree | grep jakarta
./mvnw clean test package
```

The PrimeFaces dependency output must show the `jakarta` classifier. If it does not, stop and fix that before writing more code.

---


## 6. Local development environment

### 6.6 Required local tools

The developer machine should have:

```text
mise CLI as the bootstrap tool manager
Java 25 JDK installed by mise from repository config
Maven downloaded by Maven wrapper, not globally installed
Docker Desktop or Docker Engine
Git
No host PostgreSQL installation required
```

### 6.7 Local PostgreSQL

PostgreSQL is local infrastructure, not a host prerequisite. Run it through Docker Compose and use Dockerized client commands for inspection, backup, and restore.

Create `docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:17
    container_name: shared-calendar-postgres
    environment:
      POSTGRES_DB: calendar
      POSTGRES_USER: calendar
      POSTGRES_PASSWORD: calendar
    ports:
      - "5432:5432"
    volumes:
      - calendar_postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U calendar -d calendar"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  calendar_postgres:
```

Create `.env.example`:

```bash
# Local development values
PORT=9080
COOKIE_SECURE=false
PGHOST=localhost
PGPORT=5432
PGDATABASE=calendar
PGUSER=calendar
PGPASSWORD=calendar
APP_TIMEZONE=Europe/Warsaw
APP_BASE_URL=http://localhost:9080
APP_BOOTSTRAP_ADMIN_USERNAME=admin
APP_BOOTSTRAP_ADMIN_PASSWORD=change-me-before-real-use
```

Local commands:

```bash
cp .env.example .env
set -a
source .env
set +a

mise run db
mise run dev
```

Smoke check:

```bash
curl -i http://localhost:9080/health
```

Database inspection uses the Compose service:

```bash
docker compose exec postgres psql -U calendar -d calendar -c '\dt'
docker compose exec postgres psql -U calendar -d calendar -c 'select * from flyway_schema_history order by installed_rank;'
```

---



## 7. Open Liberty configuration

Create `src/main/liberty/config/server.xml`.

Use `webProfile-10.0` first. It is simpler and ensures the needed Jakarta EE 10 web features are available. Do not prematurely micro-optimize by listing individual features unless there is a specific conflict.

```xml
<server description="shared-calendar">

    <featureManager>
        <feature>webProfile-10.0</feature>
    </featureManager>

    <variable name="PORT" defaultValue="9080"/>
    <variable name="COOKIE_SECURE" defaultValue="false"/>

    <variable name="PGHOST" defaultValue="localhost"/>
    <variable name="PGPORT" defaultValue="5432"/>
    <variable name="PGDATABASE" defaultValue="calendar"/>
    <variable name="PGUSER" defaultValue="calendar"/>
    <variable name="PGPASSWORD" defaultValue="calendar"/>

    <httpEndpoint id="defaultHttpEndpoint"
                  host="*"
                  httpPort="${PORT}"/>

    <httpSession cookieHttpOnly="true"
                 cookieSecure="${COOKIE_SECURE}"
                 cookieSameSite="Lax"
                 urlRewritingEnabled="false"/>

    <library id="postgresqlDriver">
        <fileset dir="${server.config.dir}/resources"
                 includes="postgresql-*.jar"/>
    </library>

    <dataSource id="CalendarDS" jndiName="jdbc/CalendarDS">
        <jdbcDriver libraryRef="postgresqlDriver"/>
        <properties.postgresql serverName="${PGHOST}"
                               portNumber="${PGPORT}"
                               databaseName="${PGDATABASE}"
                               user="${PGUSER}"
                               password="${PGPASSWORD}"/>
    </dataSource>

    <webApplication location="shared-calendar.war"
                    contextRoot="/"/>

</server>
```

Notes:

1. Use `host="*"`; Railway cannot reach an app bound only to localhost.
2. Use `${PORT}`. Railway injects `PORT`; Liberty environment-variable substitution can override the default.
3. Keep `COOKIE_SECURE=false` locally; set `COOKIE_SECURE=true` in production.
4. Do not hardcode production database credentials.
5. The PostgreSQL JDBC driver must be visible to Liberty as a server resource, not only as a WAR dependency. The Dockerfile below copies the driver into `/config/resources`. For local `mise run dev`, create a helper script that copies the same driver into `src/main/liberty/config/resources`; keep the copied JAR out of Git.

Create `scripts/prepare-liberty-dev.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

POSTGRESQL_VERSION="${POSTGRESQL_VERSION:-42.7.13}"
mkdir -p src/main/liberty/config/resources
./mvnw -q dependency:copy \
  -Dartifact="org.postgresql:postgresql:${POSTGRESQL_VERSION}" \
  -DoutputDirectory=src/main/liberty/config/resources
```

Add this to `.gitignore`:

```gitignore
src/main/liberty/config/resources/*.jar
```

Run this once before the first local Liberty startup and whenever the PostgreSQL driver version changes:

```bash
chmod +x scripts/prepare-liberty-dev.sh
./scripts/prepare-liberty-dev.sh
```

---


## 15. Health endpoint

Create `health/HealthServlet.java`:

```java
package com.example.calendar.health;

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
        response.setContentType("text/plain");
        response.getWriter().write("ok");
    }
}
```

Do not require database access for `/health`. This is a liveness check. Add a separate `/ready` database readiness endpoint later if needed.

---




