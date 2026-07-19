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
    @PersistenceContext(unitName = "calendarPU")
    private EntityManager entityManager;

    public Optional<CalendarRole> findActiveRole(ApplicationUser user, Long calendarId) {
        if (user == null || user.getId() == null || calendarId == null) {
            return Optional.empty();
        }

        try {
            CalendarRole role = entityManager
                    .createQuery(
                            "select calendarMember.role from CalendarMember calendarMember "
                                    + "where calendarMember.user.id = :userId "
                                    + "and calendarMember.calendar.id = :calendarId "
                                    + "and calendarMember.active = true "
                                    + "and calendarMember.calendar.active = true",
                            CalendarRole.class)
                    .setParameter("userId", user.getId())
                    .setParameter("calendarId", calendarId)
                    .getSingleResult();
            return Optional.of(role);
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    public Calendar requirePublicReadableCalendar(String publicToken) {
        if (publicToken == null || publicToken.isBlank()) {
            throw new NotFoundException("Calendar was not found.");
        }

        try {
            return entityManager
                    .createQuery(
                            "select calendarEntity from Calendar calendarEntity "
                                    + "where calendarEntity.publicToken = :publicToken "
                                    + "and calendarEntity.publicAccessEnabled = true "
                                    + "and calendarEntity.active = true",
                            Calendar.class)
                    .setParameter("publicToken", publicToken.trim())
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new NotFoundException("Calendar was not found.");
        }
    }

    public void requireCanView(ApplicationUser user, Long calendarId) {
        CalendarRole role = findActiveRole(user, calendarId)
                .orElseThrow(() -> new AuthorizationException("Calendar access is required."));
        if (!role.canView()) {
            throw new AuthorizationException("Calendar access is required.");
        }
    }

    public void requireCanEdit(ApplicationUser user, Long calendarId) {
        CalendarRole role = findActiveRole(user, calendarId)
                .orElseThrow(() -> new AuthorizationException("Editor access is required."));
        if (!role.canEdit()) {
            throw new AuthorizationException("Editor access is required.");
        }
    }

    public void requireCanAdminister(ApplicationUser user, Long calendarId) {
        CalendarRole role = findActiveRole(user, calendarId)
                .orElseThrow(() -> new AuthorizationException("Admin access is required."));
        if (!role.canAdminister()) {
            throw new AuthorizationException("Admin access is required.");
        }
    }
}
