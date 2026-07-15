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
    @PersistenceContext(unitName = "calendarPU")
    private EntityManager entityManager;

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private AuditService auditService;

    @Inject
    private CalendarMembershipPolicy calendarMembershipPolicy;

    public List<CalendarMember> listMembers(ApplicationUser actingUser, Long calendarId) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        return entityManager
                .createQuery(
                        "select calendarMember from CalendarMember calendarMember "
                                + "join fetch calendarMember.user "
                                + "where calendarMember.calendar.id = :calendarId "
                                + "order by calendarMember.user.username",
                        CalendarMember.class)
                .setParameter("calendarId", calendarId)
                .getResultList();
    }

    public Optional<CalendarMember> grantMembershipFromAcceptedInvitation(
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

        Optional<CalendarMember> existingMembership = findMembership(calendar.getId(), user.getId());
        CalendarMember member = existingMembership.orElseGet(() -> createMembership(lockedCalendar.get(), user));
        CalendarRole grantedRole = existingMembership.isPresent() && member.isActive()
                ? CalendarRole.strongerRole(member.getRole(), invitationRole)
                : invitationRole;
        applyMembership(member, grantedRole);
        return Optional.of(member);
    }

    private CalendarMember createMembership(Calendar calendar, ApplicationUser user) {
        CalendarMember newMember = new CalendarMember();
        newMember.setCalendar(calendar);
        newMember.setUser(user);
        newMember.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entityManager.persist(newMember);
        return newMember;
    }

    private void applyMembership(CalendarMember member, CalendarRole role) {
        member.setRole(role);
        member.setActive(true);
        member.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public CalendarMember changeMemberRole(ApplicationUser actingUser, Long calendarId, Long targetUserId, CalendarRole newRole) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        if (newRole == null) {
            throw new ValidationException("Role is required.");
        }

        requireLockedCalendarForMembershipChange(calendarId);
        List<CalendarMember> lockedMembers = lockMembershipsForCalendar(calendarId);
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        CalendarMember member = requireMembership(lockedMembers, targetUserId);
        requireAdminNotChangingOwnRole(actingUser, member, newRole);
        if (member.getRole() == CalendarRole.ADMIN && newRole != CalendarRole.ADMIN) {
            calendarMembershipPolicy.requireSafeRoleChange(
                    member.getRole(), newRole, anotherActiveAdminExists(lockedMembers, targetUserId));
        }

        member.setRole(newRole);
        member.setActive(true);
        member.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(actingUser, member.getCalendar(), "calendar_member", targetUserId, "role_changed", "Member role changed.");
        return member;
    }

    public void disableMember(ApplicationUser actingUser, Long calendarId, Long targetUserId) {
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        requireLockedCalendarForMembershipChange(calendarId);
        List<CalendarMember> lockedMembers = lockMembershipsForCalendar(calendarId);
        calendarAccessService.requireCanAdminister(actingUser, calendarId);
        CalendarMember member = requireMembership(lockedMembers, targetUserId);
        requireAdminNotRemovingOwnAccess(actingUser, member);
        if (member.getRole() == CalendarRole.ADMIN) {
            calendarMembershipPolicy.requireSafeDisable(member.getRole(), anotherActiveAdminExists(lockedMembers, targetUserId));
        }

        member.setActive(false);
        member.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(actingUser, member.getCalendar(), "calendar_member", targetUserId, "disabled", "Member access disabled.");
    }

    public Optional<CalendarMember> findMembership(Long calendarId, Long userId) {
        try {
            CalendarMember member = entityManager
                    .createQuery(
                            "select calendarMember from CalendarMember calendarMember "
                                    + "where calendarMember.calendar.id = :calendarId and calendarMember.user.id = :userId",
                            CalendarMember.class)
                    .setParameter("calendarId", calendarId)
                    .setParameter("userId", userId)
                    .getSingleResult();
            return Optional.of(member);
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private CalendarMember requireMembership(List<CalendarMember> members, Long userId) {
        return members.stream()
                .filter(member -> member.getUser() != null && userId.equals(member.getUser().getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Calendar membership was not found."));
    }

    private List<CalendarMember> lockMembershipsForCalendar(Long calendarId) {
        return entityManager
                .createQuery(
                        "select calendarMember from CalendarMember calendarMember "
                                + "join fetch calendarMember.user "
                                + "where calendarMember.calendar.id = :calendarId "
                                + "order by calendarMember.user.id",
                        CalendarMember.class)
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

    private boolean anotherActiveAdminExists(List<CalendarMember> members, Long targetUserId) {
        return members.stream()
                .anyMatch(member -> member.isActive()
                        && member.getRole() == CalendarRole.ADMIN
                        && member.getUser() != null
                        && !targetUserId.equals(member.getUser().getId()));
    }

    private void requireAdminNotChangingOwnRole(ApplicationUser actingUser, CalendarMember member, CalendarRole newRole) {
        if (member.getRole() == CalendarRole.ADMIN
                && newRole != CalendarRole.ADMIN
                && actingUser.getId().equals(member.getUser().getId())) {
            throw new ValidationException("You cannot change your own admin role.");
        }
    }

    private void requireAdminNotRemovingOwnAccess(ApplicationUser actingUser, CalendarMember member) {
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
