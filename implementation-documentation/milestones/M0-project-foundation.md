# M0: project foundation

Status: implemented.

## Implementation steps

1. Create the Maven WAR project, Maven Wrapper, repository metadata, and Java 25 repository-scoped toolchain.
2. Add portable `mise` tasks and the Java source-launcher helper for setup, database, development, packaging, Docker, and verification workflows.
3. Configure PostgreSQL 17 in Docker Compose and provide local environment defaults without committing secrets or database data.
4. Configure Open Liberty for Jakarta EE 10 Web Profile, Jakarta Faces extensionless routing, the PostgreSQL data source, secure session defaults, and root-context WAR deployment.
5. Add the PrimeFaces Jakarta dependency and shared PostgreSQL driver provisioning.
6. Create the shared Facelets template, base pages, responsive flat styling, and sentence-case navigation.
7. Add the health servlet and wire it to the application data source.
8. Add the initial Maven and dependency-classifier CI checks with read-only permissions.
9. Document local setup and development commands in the public README.

## Verification steps

1. Run `mise run setup`.
2. Run `mise run package`.
3. Run `mise run db`.
4. Run `mise run dev` and confirm `/health` responds.
5. Confirm PrimeFaces resolves with the `jakarta` classifier.
6. Confirm the shell and authentication pages remain usable at narrow viewport widths.
