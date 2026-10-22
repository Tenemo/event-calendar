package app.security;

import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.ValidationException;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ApiTokenServiceTest {
    private static final String FIXED_TOKEN =
            "calendar_social_api_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Test
    void issuedTokenStoresOnlyItsDigestAndHint() {
        ApplicationUser user = userWithId(19L);
        AtomicReference<ApiToken> persistedToken = new AtomicReference<>();
        ApiTokenService apiTokenService = new ApiTokenService();
        setField(apiTokenService, "entityManager", entityManagerForIssuance(user, persistedToken));
        setField(apiTokenService, "tokenService", new TokenService(new FixedSecureRandom(sequence(32))));
        apiTokenService.setClock(Clock.fixed(Instant.parse("2026-08-03T12:34:56Z"), ZoneOffset.UTC));

        IssuedApiToken issuedToken = apiTokenService.issueToken(user, "  Laptop sync  ");
        ApiToken storedToken = persistedToken.get();

        assertAll(
                () -> assertEquals(FIXED_TOKEN, issuedToken.plaintextToken()),
                () -> assertEquals("Laptop sync", storedToken.getName()),
                () -> assertEquals(
                        "524e516df1cbac31a88181fc88be1388845cd1a7e81bc9221fd426b1d0c361c1",
                        storedToken.getTokenDigest()),
                () -> assertEquals(FIXED_TOKEN.substring(FIXED_TOKEN.length() - 8), storedToken.getTokenHint()),
                () -> assertFalse(storedToken.getTokenDigest().contains(FIXED_TOKEN)),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-08-03T12:34:56Z"),
                        storedToken.getCreatedAt()),
                () -> assertEquals(41L, issuedToken.summary().id()));
    }

    @Test
    void candidateValidationRejectsModifiedOrAmbiguousCredentials() {
        assertTrue(ApiTokenService.isValidCandidate(FIXED_TOKEN));
        for (String invalidToken : new String[] {
            null,
            "",
            " " + FIXED_TOKEN,
            FIXED_TOKEN + " ",
            FIXED_TOKEN.substring(0, FIXED_TOKEN.length() - 1),
            FIXED_TOKEN + "A",
            FIXED_TOKEN.replace("calendar_social_api_", "calendar_social_api2_")
        }) {
            assertFalse(
                    ApiTokenService.isValidCandidate(invalidToken),
                    () -> "Invalid token candidate was accepted: " + invalidToken);
        }
    }

    @Test
    void issuanceRequiresARealUserAndBoundedNonblankName() {
        ApiTokenService apiTokenService = new ApiTokenService();
        setField(apiTokenService, "entityManager", entityManagerForIssuance(userWithId(1L), new AtomicReference<>()));
        setField(apiTokenService, "tokenService", new TokenService(new FixedSecureRandom(sequence(32))));

        assertThrows(AuthorizationException.class, () -> apiTokenService.issueToken(null, "Integration"));
        assertThrows(ValidationException.class, () -> apiTokenService.issueToken(userWithId(1L), "  "));
        assertThrows(
                ValidationException.class,
                () -> apiTokenService.issueToken(userWithId(1L), "x".repeat(81)));
    }

    private static EntityManager entityManagerForIssuance(
            ApplicationUser managedUser,
            AtomicReference<ApiToken> persistedToken) {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[] {EntityManager.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "find" -> managedUser;
                    case "persist" -> {
                        ApiToken apiToken = (ApiToken) arguments[0];
                        setEntityId(apiToken, 41L);
                        persistedToken.set(apiToken);
                        yield null;
                    }
                    case "flush" -> null;
                    default -> throw new AssertionError("Unexpected EntityManager call: " + method.getName());
                });
    }

    private static ApplicationUser userWithId(long id) {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, id);
        return user;
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
