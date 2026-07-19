# M2: calendar and account workflows

Status: implemented.

## Implementation steps

1. Connect registration, login, logout, and invitation acceptance pages to the security services.
2. Implement the signed-in calendar list and calendar creation flow.
3. Implement the canonical token-addressed calendar route for member and anonymous access.
4. Render persisted timed and all-day events in the calendar time zone.
5. Implement role-aware event creation, editing, and deletion with conflict handling and audit logging.
6. Implement calendar settings, public-access enablement, and canonical-link regeneration.
7. Implement editor invitations, invitation listing and revocation, member role changes, member removal, and last-admin protection.
8. Implement account settings with the signed-in password-change form and reauthentication result messages.
9. Apply the shared responsive design and accessibility behavior to every workflow.
10. Add deterministic browser coverage for authentication, password changes, calendar workflows, invitations, membership, concurrency, public links, event time handling, sessions, and responsive behavior.

## Verification steps

1. Run `mise run package`.
2. Keep `mise run dev` running and run `mise run e2e`.
3. Verify anonymous, nonmember, editor, and administrator behavior at the same canonical calendar URL.
4. Verify password change from two active sessions and confirm both require the new password afterward.
5. Verify invalid, disabled, and regenerated calendar links return the documented noindex response.
6. Verify event and membership conflict paths return user-readable messages.
