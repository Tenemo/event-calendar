# M3: production readiness

Status: implemented and deployed to Railway at `https://calendar.social`.

## Implementation steps

1. Build the multi-stage Java 25 and Open Liberty production image.
2. Add the opt-in Docker Compose application profile and JSON Liberty console logging.
3. Add portable image build, local container, backup, guarded restore, and isolated restore-verification tasks.
4. Add production-container smoke, PostgreSQL-backed browser, bootstrap-concurrency, Dependency Review, and CodeQL checks.
5. Commit Railway configuration-as-code for production builds, deploys from `master`, restart policy, and deployment health checks.
6. Provision the Railway PostgreSQL and web services with persistent database storage and referenced environment variables.
7. Deploy the application, apply Flyway migrations, and verify persistence across redeploy.
8. Configure `calendar.social`, validate DNS ownership, and confirm the Railway-managed HTTPS certificate.
9. Verify production registration, login, password change, public links, invitations, roles, event persistence, health, and secret-free logs.
10. Document deployment, environment variables, registration, roles, sessions, password changes, links, invitations, backup and restore, and troubleshooting in the public README.

## Verification steps

1. Run `mise run package`.
2. Run `mise run docker-build`.
3. Run `mise run verify-local`.
4. Run `mise run verify-backup-restore`.
5. Run `mise run e2e`.
6. Run `mise run verify-bootstrap-registration`.
7. Confirm `https://calendar.social/health` returns `200 ok` over a valid certificate.
8. Redeploy from `master`, sign in again, and confirm persisted accounts, calendars, memberships, invitations, and events remain available.
