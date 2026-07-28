package app.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class OptimisticLockConflictsTest {
    private static final String CONFLICT_MESSAGE = "The stored value changed.";

    @Test
    void acceptsOnlyTheMatchingExpectedVersion() {
        assertDoesNotThrow(() -> OptimisticLockConflicts.requireExpectedVersion(
                7, Integer.valueOf(7), CONFLICT_MESSAGE));
    }

    @Test
    void rejectsMissingAndStaleExpectedVersionsWithTheCallerMessage() {
        ConflictException missingVersion = assertThrows(
                ConflictException.class,
                () -> OptimisticLockConflicts.requireExpectedVersion(7, null, CONFLICT_MESSAGE));
        ConflictException staleVersion = assertThrows(
                ConflictException.class,
                () -> OptimisticLockConflicts.requireExpectedVersion(
                        7, Integer.valueOf(6), CONFLICT_MESSAGE));

        assertAll(
                () -> assertEquals(CONFLICT_MESSAGE, missingVersion.getMessage()),
                () -> assertEquals(CONFLICT_MESSAGE, staleVersion.getMessage()));
    }
}
