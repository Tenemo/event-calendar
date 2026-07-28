package app.membership;

import app.calendar.Calendar;
import app.calendar.CalendarLinkToken;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

@Stateless
public class CalendarAccessService {
    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    public Optional<CalendarRole> findRole(ApplicationUser user, Long calendarId) {
        if (user == null || user.getId() == null || calendarId == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(entityManager
                    .createQuery(
                            "select calendarMembership.role from CalendarMembership calendarMembership "
                                    + "where calendarMembership.user.id = :userId "
                                    + "and calendarMembership.calendar.id = :calendarId",
                            CalendarRole.class)
                    .setParameter("userId", user.getId())
                    .setParameter("calendarId", calendarId)
                    .getSingleResult());
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    public Calendar requirePublicReadableCalendar(String calendarLinkToken) {
        Calendar calendar = requireCalendarByLinkToken(calendarLinkToken);
        if (!calendar.isPublicAccessEnabled()) {
            throw notFound();
        }
        return calendar;
    }

    public Calendar requireCalendarReadableByLinkToken(
            ApplicationUser user,
            String calendarLinkToken) {
        Calendar calendar = requireCalendarByLinkToken(calendarLinkToken);
        if (calendar.isPublicAccessEnabled() || findRole(user, calendar.getId()).isPresent()) {
            return calendar;
        }
        throw notFound();
    }

    public void requireCanEdit(ApplicationUser user, Long calendarId) {
        findRole(user, calendarId)
                .orElseThrow(() -> new AuthorizationException("Editor access is required."));
    }

    public void requireCanAdminister(ApplicationUser user, Long calendarId) {
        CalendarRole role = findRole(user, calendarId)
                .orElseThrow(() -> new AuthorizationException("Admin access is required."));
        if (!role.canAdminister()) {
            throw new AuthorizationException("Admin access is required.");
        }
    }

    private Calendar requireCalendarByLinkToken(String calendarLinkToken) {
        if (!CalendarLinkToken.isValid(calendarLinkToken)) {
            throw notFound();
        }

        try {
            return entityManager
                    .createQuery(
                            "select calendarEntity from Calendar calendarEntity "
                                    + "where calendarEntity.calendarLinkToken = :calendarLinkToken",
                            Calendar.class)
                    .setParameter("calendarLinkToken", calendarLinkToken)
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw notFound();
        }
    }

    private NotFoundException notFound() {
        return new NotFoundException("Calendar was not found.");
    }
}
