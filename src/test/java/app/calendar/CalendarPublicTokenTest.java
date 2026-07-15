package app.calendar;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

final class CalendarPublicTokenTest {
    @Test
    void recognizesOnlyTheReservedElevenCharacterRootPath() {
        String token = "Abc_123-xY0";

        assertAll(
                () -> assertEquals(token, CalendarPublicToken.fromRequestPath("", "/" + token)),
                () -> assertEquals(token, CalendarPublicToken.fromRequestPath("/shared", "/shared/" + token)),
                () -> assertNull(CalendarPublicToken.fromRequestPath("", "/calendar/" + token)),
                () -> assertNull(CalendarPublicToken.fromRequestPath("", "/" + token + "/events")),
                () -> assertNull(CalendarPublicToken.fromRequestPath("", "/" + token + "a")),
                () -> assertNull(CalendarPublicToken.fromRequestPath("", "/" + token.substring(1))),
                () -> assertNull(CalendarPublicToken.fromRequestPath("", "/invalid.path")),
                () -> assertNull(CalendarPublicToken.fromRequestPath("", "/sign-in-error")),
                () -> assertNull(CalendarPublicToken.fromRequestPath("/other", "/shared/" + token)),
                () -> assertNull(CalendarPublicToken.fromRequestPath(null, "/" + token)),
                () -> assertNull(CalendarPublicToken.fromRequestPath("", null)));
    }

    @Test
    void generatedTokensContainExactlySixtyFourBitsAsUnpaddedBase64Url() {
        String token = CalendarPublicToken.generate(new FixedSecureRandom(
                new byte[] {(byte) 0xfb, (byte) 0xff, 0, 1, 2, 3, 4, 5}));

        assertAll(
                () -> assertEquals("-_8AAQIDBAU", token),
                () -> assertEquals(CalendarPublicToken.ENCODED_LENGTH, token.length()),
                () -> assertTrue(CalendarPublicToken.isValid(token)),
                () -> assertFalse(CalendarPublicToken.isValid(token + "=")),
                () -> assertFalse(CalendarPublicToken.isValid("Abc_123-xYz")),
                () -> assertFalse(CalendarPublicToken.isValid("calendar1234")));
    }

    @Test
    void rootFaceletRoutesDoNotCollideWithTheCalendarTokenNamespace() throws IOException {
        Path webApplicationRoot = Path.of("src", "main", "webapp");

        try (var rootEntries = Files.list(webApplicationRoot)) {
            rootEntries
                    .filter(path -> path.getFileName().toString().endsWith(".xhtml"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.xhtml$", ""))
                    .forEach(routeName -> assertFalse(
                            CalendarPublicToken.isValid(routeName),
                            () -> "Root route /" + routeName + " collides with canonical calendar tokens."));
        }
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte[] value;

        private FixedSecureRandom(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            System.arraycopy(value, 0, bytes, 0, bytes.length);
        }
    }
}
