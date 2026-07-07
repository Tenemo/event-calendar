# M2: calendar and admin workflows

Use this milestone to build the real calendar workflow, role-aware event CRUD, admin user management, validation, optimistic-locking messages, and audit logging for user-facing actions.

## Milestone checklist


Outcome: viewers, editors, and admins can use the calendar and admin workflows end to end, with server-side validation and audit logging.

Tasks:

1. Implement `CalendarView`.
2. Implement `CalendarEventForm`.
3. Render events in the PrimeFaces schedule.
4. Add create, edit, and delete flows with confirmation where needed.
5. Add server-side validation and user-readable optimistic-locking conflict messages.
6. Add audit logging for event create, update, and delete.
7. Implement `UserAdminView`, user table, create-user flow, role assignment, password reset, disable user, and last-admin protection.
8. Add audit logging for user-management changes.

Verification:

```bash
./mvnw clean test package
mise run db
mise run dev
curl -i http://localhost:9080/health
```

Manual role checks:

1. `VIEWER` can see the calendar.
2. `VIEWER` cannot create, edit, or delete events.
3. `EDITOR` can create, edit, and delete events.
4. `ADMIN` can create, edit, and delete events.
5. Service methods reject unauthorized mutations even if UI controls are manually triggered.
6. Admin can create viewer, editor, and admin users.
7. Viewer cannot access `/app/admin/users.xhtml`.
8. Editor cannot access `/app/admin/users.xhtml`.
9. Last admin cannot be disabled.
10. Last admin cannot lose `ADMIN`.

Acceptance criteria:

1. Events persist after restart.
2. Event time displays correctly in the configured timezone.
3. Invalid end-before-start is rejected.
4. Blank title is rejected.
5. Optimistic locking conflict shows a user-readable message.
6. User-management actions persist and are audited.
7. Last-admin protection is enforced in the service layer.



## Implementation details

## 14. JSF / PrimeFaces UI plan

### 14.1 Templates

Create `/WEB-INF/templates/main.xhtml` with:

1. `<h:head>` title slot.
2. PrimeFaces growl/messages area.
3. Header toolbar.
4. Navigation links.
5. Admin link rendered only for admins.
6. Logout button.
7. Main content slot.

Create `/WEB-INF/templates/admin.xhtml` extending or mirroring main template with admin navigation.

Do not duplicate page shell markup across pages.

### 14.2 Login page

Create `login.xhtml` with:

1. Username field.
2. Password field.
3. Submit button.
4. Generic error messages.
5. No registration link.

Use `ajax="false"` for login submit to avoid JSF/Ajax authentication edge cases.

Shape:

```xhtml
<!DOCTYPE html>
<html
  xmlns="http://www.w3.org/1999/xhtml"
  xmlns:h="jakarta.faces.html"
  xmlns:p="primefaces"
>
  <h:head>
    <title>Sign in - Shared calendar</title>
  </h:head>
  <h:body>
    <h:form id="loginForm">
      <p:panel header="Shared calendar">
        <p:messages id="messages" />

        <p:outputLabel for="username" value="Username" />
        <p:inputText
          id="username"
          value="#{loginView.username}"
          required="true"
        />

        <p:outputLabel for="password" value="Password" />
        <p:password
          id="password"
          value="#{loginView.password}"
          required="true"
          feedback="false"
        />

        <p:commandButton
          value="Sign in"
          action="#{loginView.login}"
          ajax="false"
        />
      </p:panel>
    </h:form>
  </h:body>
</html>
```

### 14.3 Calendar page

Create `event/CalendarView.java`:

1. `@Named`
2. `@ViewScoped`
3. Implements `Serializable`
4. Holds PrimeFaces schedule model.
5. Holds selected event form.
6. Delegates to `CalendarService`.
7. Does not contain business rules.

Calendar page responsibilities:

1. Show month/week/day calendar.
2. Let viewer click events and read details.
3. Let editor/admin create/edit/delete.
4. Hide edit controls for viewer.
5. Still rely on service-layer security for enforcement.

PrimeFaces schedule skeleton:

```xhtml
<p:schedule
  id="schedule"
  value="#{calendarView.eventModel}"
  widgetVar="calendarWidget"
  editable="#{currentUser.editor}"
>
  <p:ajax
    event="dateSelect"
    listener="#{calendarView.onDateSelect}"
    update="eventDialog messages"
  />

  <p:ajax
    event="eventSelect"
    listener="#{calendarView.onEventSelect}"
    update="eventDialog messages"
  />

  <p:ajax
    event="eventMove"
    listener="#{calendarView.onEventMove}"
    update="schedule messages"
    disabled="#{!currentUser.editor}"
  />

  <p:ajax
    event="eventResize"
    listener="#{calendarView.onEventResize}"
    update="schedule messages"
    disabled="#{!currentUser.editor}"
  />
</p:schedule>
```

The exact PrimeFaces schedule model API can change across versions. The agent must check the PrimeFaces 15 showcase/API and adapt the Java code accordingly.

### 14.4 Admin users page

Create `app/admin/users.xhtml` and `user/UserAdminView.java`.

Admin page capabilities:

1. List users.
2. Create user.
3. Assign/remove roles.
4. Reset password.
5. Disable user.
6. Show active/inactive state.
7. Prevent deleting users; disable instead.

Use PrimeFaces components:

```text
p:dataTable
p:dialog
p:inputText
p:password
p:selectManyCheckbox or p:selectManyMenu
p:commandButton
p:confirmDialog
p:messages
```

---



