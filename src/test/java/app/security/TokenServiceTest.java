package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.calendar.CalendarLinkToken;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

final class TokenServiceTest {
    @Test
    void generatesInvitationTokensFromExactlyThirtyTwoRandomBytes() {
        TokenService tokenService = new TokenService(new FixedSecureRandom(sequence(32)));

        String token = tokenService.generateInvitationToken();

        assertAll(
                () -> assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", token),
                () -> assertTrue(token.matches("[A-Za-z0-9_-]{43}")));
    }

    @Test
    void generatesApiTokensFromExactlyThirtyTwoRandomBytesWithARecognizablePrefix() {
        TokenService tokenService = new TokenService(new FixedSecureRandom(sequence(32)));

        String token = tokenService.generateApiToken();

        assertAll(
                () -> assertEquals(
                        "calendar_social_api_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                        token),
                () -> assertTrue(ApiTokenService.isValidCandidate(token)));
    }

    @Test
    void generatesCalendarLinkTokensFromExactlyEightRandomBytes() {
        TokenService tokenService = new TokenService(new FixedSecureRandom(
                new byte[] {(byte) 0xfb, (byte) 0xff, 0, 1, 2, 3, 4, 5}));

        String calendarLinkToken = tokenService.generateCalendarLinkToken();

        assertAll(
                () -> assertEquals("-_8AAQIDBAU", calendarLinkToken),
                () -> assertEquals(CalendarLinkToken.ENCODED_LENGTH, calendarLinkToken.length()),
                () -> assertTrue(CalendarLinkToken.isValid(calendarLinkToken)));
    }

    private static byte[] sequence(int byteCount) {
        byte[] bytes = new byte[byteCount];
        for (int byteIndex = 0; byteIndex < byteCount; byteIndex++) {
            bytes[byteIndex] = (byte) byteIndex;
        }
        return bytes;
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte[] value;

        private FixedSecureRandom(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            assertEquals(value.length, bytes.length, "Unexpected random byte request.");
            System.arraycopy(value, 0, bytes, 0, bytes.length);
        }
    }
}
