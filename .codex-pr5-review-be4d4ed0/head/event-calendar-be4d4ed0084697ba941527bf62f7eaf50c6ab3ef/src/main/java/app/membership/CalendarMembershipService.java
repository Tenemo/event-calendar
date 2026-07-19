package app.membership;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Stateless
public class CalendarMembershipService {
    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private AuditService auditService;

    public List<CalendarMembership> listMembers(ApplicationUser actingUser, Long calendarId) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        return entityManager
                .createQuery(
                        "select calendarMembership from CalendarMembership calendarMembership "
                                + "join fetch calendarMembership.user "
                                + "where calendarMembership.calendar.id = :calendarId "
                                + "order by calendarMembership.user.username",
                        CalendarMembership.class)
                .setParameter("calendarId", calendarId)
                .getResultList();
    }

    public Optional<CalendarMembership> grantMembershipFromAcceptedInvitation(
            Calendar calendar,
            ApplicationUser invitationCreator,
            ApplicationUser user,
            CalendarRole invitationRole) {
        requireValidInvitationMembership(calendar, user, invitationRole);
        Optional<Calendar> lockedCalendar = lockCalendarForMembershipChange(calendar.getId());
        if (lockedCalendar.isEmpty()
                || !lockedCalendar.get().isActive()
                || !invitationCreatorCanStillEdit(invitationCreator, calendar.getId())) {
            return Optional.empty();
        }

        Optional<CalendarMembership> existingMembership = findMembership(calendar.getId(), user.getId());
        CalendarMembership member = existingMembership.orElseGet(() -> createMembership(lockedCalendar.get(), user));
        CalendarRole grantedRole = existingMembership.isPresent() && member.isActive()
                ? CalendarRole.strongerRole(member.getRole(), invitationRole)
                : invitationRole;
        applyMembership(member, grantedRole);
        return Optional.of(member);
    }

    private CalendarMembership createMembership(Calendar calendar, ApplicationUser user) {
        CalendarMembership newMember = new CalendarMembership();
        newMember.setCalendar(calendar);
        newMember.setUser(user);
        newMember.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entityManager.persist(newMember);
        return newMember;
    }

    private void applyMembership(CalendarMembership member, CalendarRole role) {
        member.setRole(role);
        member.setActive(true);
        member.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public CalendarMembership changeMemberRole(ApplicationUser actingUser, Long calendarId, Long targetUserId, CalendarRole newRole) {
        return updateMemberRole(actingUser, calendarId, targetUserId, newRole, false);
    }

    public CalendarMembership reactivateMembership(
            ApplicationUser actingUser,
            Long calendarId,
            Long targetUserId,
            CalendarRole newRole) {
        return updateMemberRole(actingUser, calendarId, targetUserId, newRole, true);
    }

    private CalendarMembership updateMemberRole(
            ApplicationUser actingUser,
            Long calendarId,
            Long targetUserId,
            CalendarRole newRole,
            boolean reactivationRequested) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        if (newRole == null) {
            throw new ValidationException("Role is required.");
        }

        requireLockedCalendarForMembershipChange(calendarId);
        List<CalendarMembership> lockedMembers = lockMembershipsForCalendar(calendarId);
        // Administration can be revoked while this transaction waits for the calendar lock.
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        CalendarMembership member = requireMembership(lockedMembers, targetUserId);
        if (member.isActive() == reactivationRequested) {
            throw new ValidationException(reactivationRequested
                    ? "Calendar membership is already active."
                    : "Inactive calendar membership must be reactivated explicitly.");
        }
        requireAdminNotChangingOwnRole(actingUser, member, newRole);
        if (member.getRole() == CalendarRole.ADMIN && newRole != CalendarRole.ADMIN) {
            requireAnotherActiveAdmin(lockedMembers, targetUserId);
        }

        member.setRole(newRole);
        member.setActive(true);
        member.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(
                actingUser,
                member.getCalendar(),
                "calendar_member",
                targetUserId,
                reactivationRequested ? "reactivated" : "role_changed",
                reactivationRequested ? "Member access reactivated." : "Member role changed.");
        return member;
    }

    public void disableMembership(ApplicationUser actingUser, Long calendarId, Long targetUserId) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        requireLockedCalendarForMembershipChange(calendarId);
        List<CalendarMembership> lockedMembers = lockMembershipsForCalendar(calendarId);
        // Administration can be revoked while this transaction waits for the calendar lock.
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        CalendarMembership member = requireMembership(lockedMembers, targetUserId);
        requireAdminNotRemovingOwnAccess(actingUser, member);
        if (member.getRole() == CalendarRole.ADMIN) {
            requireAnotherActiveAdmin(lockedMembers, targetUserId);
        }

        member.setActive(false);
        member.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(actingUser, member.getCalendar(), "calendar_member", targetUserId, "disabled", "Member access disabled.");
    }

    private Optional<CalendarMembership> findMembership(Long calendarId, Long userId) {
        try {
            CalendarMembership member = entityManager
                    .createQuery(
                            "select calendarMembership from CalendarMembership calendarMembership "
                                    + "where calendarMembership.calendar.id = :calendarId and calendarMembership.user.id = :userId",
                            CalendarMembership.class)
                    .setParameter("calendarId", calendarId)
                    .setParameter("userId", userId)
                    .getSingleResult();
            return Optional.of(member);
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private CalendarMembership requireMembership(List<CalendarMembership> members, Long userId) {
        return members.stream()
                .filter(member -> member.getUser() != null && userId.equals(member.getUser().getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Calendar membership was not found."));
    }

    private List<CalendarMembership> lockMembershipsForCalendar(Long calendarId) {
        return entityManager
                .createQuery(
                        "select calendarMembership from CalendarMembership calendarMembership "
                                + "join fetch calendarMembership.user "
                                + "where calendarMembership.calendar.id = :calendarId "
                                + "order by calendarMembership.user.id",
                        CalendarMembership.class)
                .setParameter("calendarId", calendarId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
    }

    private Optional<Calendar> lockCalendarForMembershipChange(Long calendarId) {
        try {
            Calendar calendar = entityManager
                    .createQuery(
                            "select calendarEntity from Calendar calendarEntity "
                                    + "where calendarEntity.id = :calendarId",
                            Calendar.class)
                    .setParameter("calendarId", calendarId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getSingleResult();
            return Optional.of(calendar);
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private Calendar requireLockedCalendarForMembershipChange(Long calendarId) {
        return lockCalendarForMembershipChange(calendarId)
                .orElseThrow(() -> new NotFoundException("Calendar was not found."));
    }

    private boolean invitationCreatorCanStillEdit(ApplicationUser invitationCreator, Long calendarId) {
        if (invitationCreator == null
                || invitationCreator.getId() == null
                || !invitationCreator.isActive()) {
            return false;
        }

        try {
            calendarAccessService.requireCanEdit(invitationCreator, calendarId);
            return true;
        } catch (AuthorizationException exception) {
            return false;
        }
    }

    private boolean anotherActiveAdminExists(List<CalendarMembership> members, Long targetUserId) {
        return members.stream()
                .anyMatch(member -> member.isActive()
                        && member.getRole() == CalendarRole.ADMIN
                        && member.getUser() != null
                        && !targetUserId.equals(member.getUser().getId()));
    }

    private void requireAnotherActiveAdmin(List<CalendarMembership> members, Long targetUserId) {
        if (!anotherActiveAdminExists(members, targetUserId)) {
            throw new ValidationException("A calendar must keep at least one active admin.");
        }
    }

    private void requireAdminNotChangingOwnRole(ApplicationUser actingUser, CalendarMembership member, CalendarRole newRole) {
        if (member.getRole() == CalendarRole.ADMIN
                && newRole != CalendarRole.ADMIN
                && actingUser.getId().equals(member.getUser().getId())) {
            throw new ValidationException("You cannot change your own admin role.");
        }
    }

    private void requireAdminNotRemovingOwnAccess(ApplicationUser actingUser, CalendarMembership member) {
        if (member.getRole() == CalendarRole.ADMIN && actingUser.getId().equals(member.getUser().getId())) {
            throw new ValidationException("You cannot remove your own admin access.");
        }
    }

    private void requireValidInvitationMembership(Calendar calendar, ApplicationUser user, CalendarRole role) {
        requireValidMembership(calendar, user, role);
        if (role != CalendarRole.EDITOR) {
            throw new ValidationException("Invitations can only grant editor access.");
        }
    }

    private void requireValidMembership(Calendar calendar, ApplicationUser user, CalendarRole role) {
        if (calendar == null || calendar.getId() == null || !calendar.isActive()) {
            throw new ValidationException("An active calendar is required.");
        }
        if (user == null || user.getId() == null || !user.isActive()) {
            throw new ValidationException("An active user is required.");
        }
        if (role == null) {
            throw new ValidationException("Role is required.");
        }
    }
}
