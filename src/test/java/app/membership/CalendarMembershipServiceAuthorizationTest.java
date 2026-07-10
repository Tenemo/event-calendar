package app.membership;

import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.user.AppUser;
import app.util.AuthorizationException;
import org.junit.jupiter.api.Test;

final class CalendarMembershipServiceAuthorizationTest {
    @Test
    void memberListingRequiresAdminAccessBeforeQueryingMembers() {
        CalendarMembershipService membershipService = new CalendarMembershipService();
        setField(membershipService, "calendarAccessService", new RejectingAccessService());

        assertThrows(AuthorizationException.class, () -> membershipService.listMembers(activeUser(), 10L));
    }

    @Test
    void roleChangesRequireAdminAccessBeforeChangingMembers() {
        CalendarMembershipService membershipService = new CalendarMembershipService();
        setField(membershipService, "calendarAccessService", new RejectingAccessService());

        assertThrows(
                AuthorizationException.class,
                () -> membershipService.changeMemberRole(activeUser(), 10L, 20L, CalendarRole.EDITOR));
    }

    private static AppUser activeUser() {
        AppUser user = new AppUser();
        setEntityId(user, 20L);
        user.setUsername("editor");
        user.setDisplayName("Editor");
        user.setActive(true);
        return user;
    }

    private static final class RejectingAccessService extends CalendarAccessService {
        @Override
        public void requireCanAdminister(AppUser user, Long calendarId) {
            throw new AuthorizationException("Admin access is required.");
        }
    }
}
