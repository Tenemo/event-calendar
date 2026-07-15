# M2: calendar and member workflows

Status: implemented and locally verified.

Use this milestone to build the real user-facing workflows on top of the M1 services: public calendar viewing, invitation-only registration and login screens, signed-in calendar list, calendar creation, event CRUD, invite links, invite acceptance, calendar settings, and member management.

## Delivered implementation

1. Public calendars use `/calendar/{publicToken}`, return generic 404 pages for invalid or disabled tokens, and include `noindex, nofollow`.
2. Authenticated calendar pages render persisted events in the calendar timezone and expose create, edit, and delete actions only to editors and admins.
3. Calendar-local timed input is converted to offset-aware storage, with invalid and ambiguous daylight-saving times rejected. Inclusive all-day form dates use calendar-local start boundaries and an exclusive stored end on the following day, including across daylight-saving transitions and Java-only time zones.
4. Event and calendar writes compare entity versions and translate optimistic-lock failures into user-readable reload messages.
5. Calendar settings cover name, description, IANA timezone, public access, and audited public-token rotation.
6. Member administration covers role changes, reactivation, access removal, audit logging, and service-level last-admin protection.
7. App-only and calendar-editor invitations expire after seven days and can be accepted by either new or existing users. Creators can revoke unused links; calendar admins can list and revoke unused editor links; creator permission is revalidated during serialized acceptance.
8. Public access can be disabled and re-enabled without losing its bearer token, while rotation immediately invalidates the previous URL; every public state remains read-only.
9. The Playwright suite exercises the stable routes and complete invitation, calendar, event, public-link, role, session, concurrency, and accessibility workflows against PostgreSQL and Liberty.

## Milestone checklist

Outcome: public visitors, registered users, editors, and calendar admins can use the calendar workflows end to end, with server-side validation and audit logging.

Tasks:

1. Implement invitation-only registration and login pages against the M1 services.
2. Implement `CalendarListView` for signed-in users.
3. Implement calendar creation flow.
4. Implement public calendar view by public token.
5. Implement authenticated calendar detail view.
6. Render events in the PrimeFaces schedule or a PrimeFaces-backed calendar layout.
7. Add create, edit, and delete event flows with confirmation where needed.
8. Add server-side validation and user-readable optimistic-locking conflict messages.
9. Add audit logging for event create, update, and delete.
10. Implement calendar member page with member table, role changes, access removal, and last-admin protection.
11. Implement invite acceptance for signed-in users and newly registered users.
12. Implement calendar settings page with name, description, timezone, public-link enable/disable, and public-link rotation.
13. Style the UI as a modern flat app, not a marketing page.
14. Add or prepare a deterministic CI app-smoke check for the real HTTP routes.

Verification:

```bash
./mvnw clean test package
mise run db
mise run dev
curl -i http://localhost:9080/health
```

Manual role checks:

1. Public visitor with valid link can see the calendar read-only.
2. Public visitor cannot create, edit, delete, or manage members.
3. Invalid public token shows a generic not-found page.
4. Registered user can create a calendar.
5. Calendar creator becomes calendar admin.
6. Public visitors can see public calendars without sign-in.
7. Public visitors cannot create, edit, delete, or manage members.
8. `EDITOR` member can create, edit, and delete events.
9. `EDITOR` member can create editor invitation links for calendars they can edit.
10. `EDITOR` member cannot manage members.
11. `ADMIN` member can change member roles.
12. Last calendar admin cannot be disabled or demoted.
13. Service methods reject unauthorized mutations even if UI controls are manually triggered.
14. Calendar admin can see and revoke another creator's unused editor invitation.
15. Removing an editor's access invalidates editor invitations they previously created.
16. Disabling or rotating public access makes the affected public URL return a generic not-found response without affecting authenticated access.

Acceptance criteria:

1. Users, calendars, memberships, invitations, and events persist after restart.
2. Public calendar links work without login.
3. Public calendar pages include `noindex`.
4. Event time displays correctly in the configured calendar timezone.
5. Invalid end-before-start is rejected.
6. Blank title is rejected.
7. Optimistic locking conflict shows a user-readable message.
8. App invitation actions persist and are audited.
9. Member-management actions persist and are audited.
10. Last-admin protection is enforced in the service layer.
11. The UI is responsive and uses the flat design system from M0.
12. App-smoke CI covers health and stable route-level behavior without brittle sleeps or flaky browser automation.
13. All-day first and last form dates remain inclusive while stored end boundaries are exclusive and normalized in the calendar time zone.
14. Concurrent acceptance of one invitation has exactly one winner, and concurrent distinct invitations do not create duplicate memberships.

## JSF / PrimeFaces UI plan

### Templates

Use `/WEB-INF/templates/main.xhtml` for:

1. `<h:head>` title slot.
2. Shared stylesheet.
3. PrimeFaces messages area.
4. Header toolbar.
5. Navigation links.
6. Main content slot.

Do not duplicate page shell markup across pages.

### Registration page

Create `register.xhtml` with:

1. Username field.
2. Display name field.
3. Password field.
4. Initial calendar name field.
5. Submit button.
6. Link to sign in.
7. Generic, user-readable validation messages.

Use `ajax="false"` for submit unless the authentication flow has been verified with JSF Ajax.

### Login page

Create `login.xhtml` with:

1. Username field.
2. Password field.
3. Submit button.
4. Generic error messages.
5. Link to register.

Never reveal whether username or password was wrong.

### Public calendar page

Use `public-calendar.xhtml` behind the servlet-backed `/calendar/{publicToken}` route.

Responsibilities:

1. Load calendar by public token.
2. Return a generic not-found state for invalid, disabled, or inactive public links.
3. Render calendar name, description, timezone, and events.
4. Hide all mutation and member controls.
5. Include a `noindex` meta tag.

### Calendar list page

Create `app/calendars.xhtml` and `calendar/CalendarListView.java`.

Responsibilities:

1. List calendars where the signed-in user has active membership.
2. Show each calendar role.
3. Link to authenticated calendar detail.
4. Provide create-calendar flow.
5. Show public-link copy affordance for admins.

### Calendar detail page

Create `app/calendar.xhtml` and `calendar/CalendarView.java`.

Responsibilities:

1. Show month/week/day calendar or a clear event list if PrimeFaces schedule integration needs incremental delivery.
2. Let public visitors read event details through public links.
3. Let editors/admins create/edit/delete events.
4. Hide edit controls for public visitors.
5. Still rely on service-layer security for enforcement.
6. Show inclusive first and last date inputs for all-day events while services store exclusive calendar-local end boundaries.

The exact PrimeFaces schedule model API can change across versions. Check the PrimeFaces 15 showcase/API and adapt the Java code accordingly.

### Calendar members page

Create `app/calendar-members.xhtml` and `membership/CalendarMembersView.java`.

Calendar admins can:

1. List members.
2. Change member roles.
3. Disable member access.
4. See active/inactive state.
5. See and revoke unused editor invitations for the calendar, including invitations created by another editor.

Use PrimeFaces components:

```text
p:dataTable
p:dialog
p:inputText
p:selectOneMenu
p:commandButton
p:confirmDialog
p:messages
```

Do not build email delivery. Invite links are copied manually.

### App invitations page

Create `app/invitations.xhtml` and `invitation/InvitationView.java`.

Signed-in users can:

1. Generate app-only invitation links.
2. Generate editor invitation links for calendars where they have `EDITOR` or `ADMIN`.
3. Revoke their own unused invitation links.
4. See seven-day expiry and current invitation status.

Calendar admins also see unused editor invitations for calendars they administer and can revoke them. Every invitation stops working when its creator's account becomes inactive. Calendar editor invitations never grant `VIEWER`, also stop working when their creator loses edit permission, and serialize acceptance so only one account can consume a link.

Do not build email delivery. Invitation links are copied manually.

### Calendar settings page

Create `app/calendar-settings.xhtml` and `calendar/CalendarSettingsView.java`.

Calendar admins can:

1. Rename a calendar.
2. Edit description.
3. Set timezone.
4. Enable or disable public link access.
5. Rotate the public link.

Disabling public access preserves the token for safe re-enablement while returning a generic not-found response publicly. Rotating the public link invalidates the previous public URL immediately. Both changes must be audited and cannot expose mutation controls.

## GitHub PR checks after M2

Keep the M0 Maven build check and the M1 PostgreSQL-backed check required. Add an app-smoke lane once the real pages are implemented and can start reliably in CI.

The app-smoke lane should:

1. Start PostgreSQL with the same test environment values used by the database lane.
2. Start Open Liberty from the Maven wrapper or the same committed project task used locally.
3. Wait for `/health` with a bounded readiness loop.
4. Check stable HTTP behavior for public calendar, registration, login, authenticated workspace, and invalid public-token routes.
5. Keep assertions route-level at first; do not require full browser end-to-end tests until the UI flows are deterministic.

Do not add retries to mask route or startup flakiness. If the app-smoke lane flakes, fix startup readiness, test isolation, database state, or session handling before making it required.
