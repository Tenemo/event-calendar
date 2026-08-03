package app.calendar;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.membership.CalendarAccessService;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

final class CalendarServiceTest {
    @Test
    void regenerationRechecksAdminPermissionAfterLockingTheCalendar() {
        Calendar calendar = new Calendar();
        RecordingCalendarAccessService calendarAccessService =
                new RecordingCalendarAccessService();
        CalendarService calendarService = new CalendarService();
        setField(calendarService, "calendarAccessService", calendarAccessService);
        setField(calendarService, "entityManager", entityManagerReturning(calendar));

        assertThrows(
                AuthorizationException.class,
                () -> calendarService.regenerateCalendarLink(
                        new ApplicationUser(), 42L, 0));

        assertEquals(2, calendarAccessService.adminCheckCount);
        assertEquals(0, calendarAccessService.editorCheckCount);
    }

    private EntityManager entityManagerReturning(Calendar calendar) {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[] {EntityManager.class},
                (proxy, method, arguments) -> {
                    if ("find".equals(method.getName()) && arguments != null && arguments.length == 3) {
                        return calendar;
                    }
                    throw new AssertionError("Unexpected EntityManager call: " + method.getName());
                });
    }

    private static final class RecordingCalendarAccessService extends CalendarAccessService {
        private int adminCheckCount;
        private int editorCheckCount;

        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
            editorCheckCount++;
        }

        @Override
        public void requireCanAdminister(ApplicationUser user, Long calendarId) {
            adminCheckCount++;
            if (adminCheckCount == 2) {
                throw new AuthorizationException("Admin access is required.");
            }
        }
    }
}
