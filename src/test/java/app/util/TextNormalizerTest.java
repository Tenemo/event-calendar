package app.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class TextNormalizerTest {
    @Test
    void multilineLimitsUseBrowserLogicalLineEndingsAndUtf16CodeUnits() {
        String normalizedPrefix = "A\n\uD801\uDC37\nB";
        String normalizedMaximumLengthDescription = normalizedPrefix
                + "d".repeat(TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH - normalizedPrefix.length());
        String browserPostedDescription = "A\r\n\uD801\uDC37\rB"
                + "d".repeat(TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH - normalizedPrefix.length());

        String normalizedDescription = TextNormalizer.normalizeOptionalMultilineText(
                browserPostedDescription,
                TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH,
                "Description is too long.");
        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> TextNormalizer.normalizeOptionalMultilineText(
                        browserPostedDescription + "x",
                        TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH,
                        "Description is too long."));

        assertAll(
                () -> assertEquals(
                        TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH,
                        normalizedMaximumLengthDescription.length()),
                () -> assertEquals(
                        TextNormalizer.MAXIMUM_DESCRIPTION_LENGTH + 1,
                        browserPostedDescription.length()),
                () -> assertEquals(normalizedMaximumLengthDescription, normalizedDescription),
                () -> assertEquals("Description is too long.", exception.getMessage()));
    }

    @Test
    void singleLineNormalizationDoesNotRewriteCarriageReturns() {
        assertEquals(
                "North\rSouth",
                TextNormalizer.normalizeOptionalText(
                        "North\rSouth",
                        20,
                        "Location is too long."));
    }
}
