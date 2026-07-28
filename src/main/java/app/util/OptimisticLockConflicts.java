package app.util;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;

/**
 * Translates lost optimistic-locking races into {@link ConflictException} with the caller's own
 * message.
 *
 * <p>Both halves of this live here so they cannot drift apart. The version comparison unboxes
 * explicitly rather than relying on the operand order of a short-circuiting guard, and the flush
 * translation is the single place where a concurrent edit becomes a user-facing conflict.
 */
public final class OptimisticLockConflicts {
    private OptimisticLockConflicts() {
    }

    /** Rejects a submitted form whose entity version is missing or no longer matches the stored row. */
    public static void requireExpectedVersion(int actualVersion, Integer expectedVersion, String conflictMessage) {
        if (expectedVersion == null || actualVersion != expectedVersion.intValue()) {
            throw new ConflictException(conflictMessage);
        }
    }

    public static void flushOrConflict(EntityManager entityManager, String conflictMessage) {
        try {
            entityManager.flush();
        } catch (OptimisticLockException exception) {
            throw new ConflictException(conflictMessage);
        }
    }
}
