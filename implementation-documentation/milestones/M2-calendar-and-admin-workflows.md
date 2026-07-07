# M2: calendar and member workflows

Use this milestone to build the real user-facing workflows on top of the M1 services: public calendar viewing, registration and login screens, signed-in calendar list, calendar creation, event CRUD, invite links, invite acceptance, calendar settings, and member management.

## Milestone checklist

Outcome: public visitors, registered users, viewers, editors, and calendar admins can use the calendar workflows end to end, with server-side validation and audit logging.

Tasks:

1. Implement registration and login pages against the M1 services.
2. Implement `CalendarListView` for signed-in users.
3. Implement calendar creation flow.
4. Implement public calendar view by public token.
5. Implement authenticated calendar detail view.
6. Render events in the PrimeFaces schedule or a PrimeFaces-backed calendar layout.
7. Add create, edit, and delete event flows with confirmation where needed.
8. Add server-side validation and user-readable optimistic-locking conflict messages.
9. Add audit logging for event create, update, and delete.
10. Implement calendar member page with member table, invite-link creation, role changes, access removal, and last-admin protection.
11. Implement invite acceptance for signed-in users and newly registered users.
12. Implement calendar settings page with name, description, timezone, public-link enable/disable, and public-link rotation.
13. Style the UI as a modern flat app, not a marketing page.

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
6. `VIEWER` member can see the calendar.
7. `VIEWER` member cannot create, edit, or delete events.
8. `EDITOR` member can create, edit, and delete events.
9. `EDITOR` member cannot manage invites or members.
10. `ADMIN` member can create invite links.
11. `ADMIN` member can change member roles.
12. Last calendar admin cannot be disabled or demoted.
13. Service methods reject unauthorized mutations even if UI controls are manually triggered.

Acceptance criteria:

1. Users, calendars, memberships, invitations, and events persist after restart.
2. Public calendar links work without login.
3. Public calendar pages include `noindex`.
4. Event time displays correctly in the configured calendar timezone.
5. Invalid end-before-start is rejected.
6. Blank title is rejected.
7. Optimistic locking conflict shows a user-readable message.
8. Invite-link actions persist and are audited.
9. Member-management actions persist and are audited.
10. Last-admin protection is enforced in the service layer.
11. The UI is responsive and uses the flat design system from M0.

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

Create `public-calendar.xhtml` or a servlet-backed route for `/calendar/{publicToken}`.

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
2. Let public and member viewers read event details.
3. Let editors/admins create/edit/delete events.
4. Hide edit controls for viewers.
5. Still rely on service-layer security for enforcement.

The exact PrimeFaces schedule model API can change across versions. Check the PrimeFaces 15 showcase/API and adapt the Java code accordingly.

### Calendar members page

Create `app/calendar-members.xhtml` and `membership/CalendarMembersView.java`.

Calendar admins can:

1. List members.
2. Generate viewer/editor invite links.
3. Revoke invite links.
4. Change member roles.
5. Disable member access.
6. See active/inactive state.

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

### Calendar settings page

Create `app/calendar-settings.xhtml` and `calendar/CalendarSettingsView.java`.

Calendar admins can:

1. Rename a calendar.
2. Edit description.
3. Set timezone.
4. Enable or disable public link access.
5. Rotate the public link.

Rotating the public link invalidates the previous public URL and must be audited.
