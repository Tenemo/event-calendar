# M1: persistence and security core

Status: implemented through Flyway migration 9.

## Implementation steps

1. Add Flyway startup migration support and provider-neutral Jakarta Persistence configuration.
2. Create migrations for users, calendars, memberships, events, invitations, audit records, permanent bootstrap admission, normalized all-day bounds, the final two-role membership model, and password-version session revocation.
3. Map the schema with provider-neutral JPA entities and optimistic-lock versions where concurrent editing requires them.
4. Implement password hashing and policy validation through Jakarta Security PBKDF2.
5. Implement invitation-only registration, transactional bootstrap admission, login, logout, current-user resolution, and source-aware login throttling.
6. Implement rolling 30-day authenticated sessions and database-backed password-version validation for session revocation.
7. Implement signed-in password changes with current-password verification, new-password confirmation, policy enforcement, audit logging, and reauthentication.
8. Implement calendar creation, token generation, membership authorization, invitation admission, event validation, and audit services.
9. Serialize bootstrap and invitation acceptance paths and protect the final active calendar administrator.
10. Add focused tests for persistence, authentication, password changes, session revocation, authorization, invitations, tokens, event validation, health, and time handling.
11. Add deterministic PostgreSQL-backed CI coverage for migrations and security-sensitive service behavior.

## Verification steps

1. Run `mise run package`.
2. Run `mise run db` and inspect Flyway history.
3. Run `mise run dev` against an empty migrated database.
4. Verify registration, login, password change, reauthentication, calendar creation, and logout.
5. Run `mise run verify-bootstrap-registration`.
6. Confirm database unavailability changes `/health` from `200` to `503` without exposing connection details.
