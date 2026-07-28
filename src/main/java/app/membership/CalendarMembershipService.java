package app.membership;

import app.calendar.Calendar;
import app.user.ApplicationUser;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

@Stateless
public class CalendarMembershipService {
    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    @Inject
    private CalendarAccessService calendarAccessService;

    public List<CalendarMembership> listMembers(ApplicationUser actingUser, Long calendarId) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        return List.copyOf(entityManager
                .createQuery(
                        "select calendarMembership from CalendarMembership calendarMembership "
                                + "join fetch calendarMembership.user "
                                + "where calendarMembership.calendar.id = :calendarId "
                                + "order by calendarMembership.user.displayName, calendarMembership.user.username",
                        CalendarMembership.class)
                .setParameter("calendarId", calendarId)
                .getResultList());
    }

    public Optional<CalendarMembership> grantEditorMembershipFromInvitation(
            Calendar calendar,
            ApplicationUser invitationCreator,
            ApplicationUser user) {
        if (calendar == null || calendar.getId() == null) {
            throw new ValidationException("Calendar is required.");
        }
        if (invitationCreator == null || invitationCreator.getId() == null) {
            throw new ValidationException("Invitation creator is required.");
        }
        if (user == null || user.getId() == null) {
            throw new ValidationException("User is required.");
        }

        Calendar lockedCalendar = entityManager.find(
                Calendar.class, calendar.getId(), LockModeType.PESSIMISTIC_WRITE);
        if (lockedCalendar == null || !canEdit(invitationCreator, calendar.getId())) {
            return Optional.empty();
        }

        Optional<CalendarMembership> existingMembership = findMembership(calendar.getId(), user.getId());
        if (existingMembership.isPresent()) {
            return existingMembership;
        }

        ApplicationUser managedUser = entityManager.find(ApplicationUser.class, user.getId());
        if (managedUser == null) {
            return Optional.empty();
        }
        CalendarMembership membership = new CalendarMembership();
        membership.setCalendar(lockedCalendar);
        membership.setUser(managedUser);
        membership.setRole(CalendarRole.EDITOR);
        entityManager.persist(membership);
        return Optional.of(membership);
    }

    public CalendarMembership changeMemberRole(
            ApplicationUser actingUser,
            Long calendarId,
            Long targetUserId,
            CalendarRole newRole) {
        if (newRole == null) {
            throw new ValidationException("Role is required.");
        }
        lockCalendarAndRequireAdmin(actingUser, calendarId);
        CalendarMembership membership = requireMembership(calendarId, targetUserId);
        if (membership.getRole() == CalendarRole.ADMIN && newRole != CalendarRole.ADMIN) {
            requireAnotherAdmin(calendarId, targetUserId);
        }
        membership.setRole(newRole);
        return membership;
    }

    public void removeMembership(
            ApplicationUser actingUser,
            Long calendarId,
            Long targetUserId) {
        lockCalendarAndRequireAdmin(actingUser, calendarId);
        CalendarMembership membership = requireMembership(calendarId, targetUserId);
        if (membership.getRole() == CalendarRole.ADMIN) {
            requireAnotherAdmin(calendarId, targetUserId);
        }
        entityManager.remove(membership);
    }

    private void lockCalendarAndRequireAdmin(ApplicationUser actingUser, Long calendarId) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        Calendar calendar = calendarId == null
                ? null
                : entityManager.find(Calendar.class, calendarId, LockModeType.PESSIMISTIC_WRITE);
        if (calendar == null) {
            throw new NotFoundException("Calendar was not found.");
        }
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
    }

    private Optional<CalendarMembership> findMembership(Long calendarId, Long userId) {
        try {
            return Optional.of(entityManager
                    .createQuery(
                            "select calendarMembership from CalendarMembership calendarMembership "
                                    + "where calendarMembership.calendar.id = :calendarId "
                                    + "and calendarMembership.user.id = :userId",
                            CalendarMembership.class)
                    .setParameter("calendarId", calendarId)
                    .setParameter("userId", userId)
                    .getSingleResult());
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private CalendarMembership requireMembership(Long calendarId, Long userId) {
        if (userId == null) {
            throw new NotFoundException("Calendar membership was not found.");
        }
        return findMembership(calendarId, userId)
                .orElseThrow(() -> new NotFoundException("Calendar membership was not found."));
    }

    private void requireAnotherAdmin(Long calendarId, Long excludedUserId) {
        Long otherAdminCount = entityManager
                .createQuery(
                        "select count(calendarMembership) from CalendarMembership calendarMembership "
                                + "where calendarMembership.calendar.id = :calendarId "
                                + "and calendarMembership.role = :adminRole "
                                + "and calendarMembership.user.id <> :excludedUserId",
                        Long.class)
                .setParameter("calendarId", calendarId)
                .setParameter("adminRole", CalendarRole.ADMIN)
                .setParameter("excludedUserId", excludedUserId)
                .getSingleResult();
        if (otherAdminCount == 0) {
            throw new ValidationException("A calendar must keep at least one admin.");
        }
    }

    private boolean canEdit(ApplicationUser user, Long calendarId) {
        return calendarAccessService.findRole(user, calendarId).isPresent();
    }

}
