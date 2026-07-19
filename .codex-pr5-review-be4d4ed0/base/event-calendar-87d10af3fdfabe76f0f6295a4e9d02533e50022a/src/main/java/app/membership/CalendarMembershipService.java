package app.membership;

import app.audit.AuditService;
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

    public CalendarMember grantMembershipFromAcceptedInvitation(Calendar calendar, ApplicationUser user, CalendarRole invitationRole) {
        requireValidInvitationMembership(calendar, user, invitationRole);
        Optional<CalendarMember> existingMembership = findMembership(calendar.getId(), user.getId());
        CalendarMember member = existingMembership.orElseGet(() -> createMembership(calendar, user));
        CalendarRole grantedRole = existingMembership.isPresent() && member.isActive()
                ? CalendarRole.strongerRole(member.getRole(), invitationRole)
                : invitationRole;
        applyMembership(member, grantedRole);
        return member;
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
        if (role == CalendarRole.ADMIN) {
            throw new ValidationException("Invitations can only grant viewer or editor access.");
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
