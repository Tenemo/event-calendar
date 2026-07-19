package app.membership;

import app.calendar.Calendar;
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

    public Optional<CalendarRole> findActiveRole(ApplicationUser user, Long calendarId) {
        if (user == null || user.getId() == null || calendarId == null) {
            return Optional.empty();
        }

        try {
            CalendarRole role = entityManager
                    .createQuery(
                            "select calendarMembership.role from CalendarMembership calendarMembership "
                                    + "where calendarMembership.user.id = :userId "
                                    + "and calendarMembership.calendar.id = :calendarId "
                                    + "and calendarMembership.active = true "
                                    + "and calendarMembership.user.active = true "
                                    + "and calendarMembership.calendar.active = true",
                            CalendarRole.class)
                    .setParameter("userId", user.getId())
                    .setParameter("calendarId", calendarId)
                    .getSingleResult();
            return Optional.of(role);
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    public Calendar requirePublicReadableCalendar(String calendarLinkToken) {
        Calendar calendar = requireActiveCalendarByCalendarLinkToken(calendarLinkToken);
        if (!calendar.isPublicAccessEnabled()) {
            throw new NotFoundException("Calendar was not found.");
        }
        return calendar;
    }

    public Calendar requireCalendarReadableByLinkToken(ApplicationUser user, String calendarLinkToken) {
        Calendar calendar = requireActiveCalendarByCalendarLinkToken(calendarLinkToken);
        if (calendar.isPublicAccessEnabled() || findActiveRole(user, calendar.getId()).isPresent()) {
            return calendar;
        }
        throw new NotFoundException("Calendar was not found.");
    }

    public void requireCanEdit(ApplicationUser user, Long calendarId) {
        findActiveRole(user, calendarId)
                .orElseThrow(() -> new AuthorizationException("Editor access is required."));
    }

    public void requireCanAdminister(ApplicationUser user, Long calendarId) {
        CalendarRole role = findActiveRole(user, calendarId)
                .orElseThrow(() -> new AuthorizationException("Admin access is required."));
        if (!role.canAdminister()) {
            throw new AuthorizationException("Admin access is required.");
        }
    }

    private Calendar requireActiveCalendarByCalendarLinkToken(String calendarLinkToken) {
        if (calendarLinkToken == null || calendarLinkToken.isBlank()) {
            throw new NotFoundException("Calendar was not found.");
        }

        try {
            return entityManager
                    .createQuery(
                            "select calendarEntity from Calendar calendarEntity "
                                    + "where calendarEntity.calendarLinkToken = :calendarLinkToken "
                                    + "and calendarEntity.active = true",
                            Calendar.class)
                    .setParameter("calendarLinkToken", calendarLinkToken.trim())
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new NotFoundException("Calendar was not found.");
        }
    }
}
