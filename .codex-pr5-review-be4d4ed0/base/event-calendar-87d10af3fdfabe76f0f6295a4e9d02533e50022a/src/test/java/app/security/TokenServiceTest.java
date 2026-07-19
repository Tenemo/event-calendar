package app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TokenServiceTest {
    private final TokenService tokenService = new TokenService();

    @Test
    void generatesUniqueUrlSafeBearerTokens() {
        int generatedTokenCount = 256;
        Set<String> tokens = new HashSet<>();

        for (int tokenNumber = 0; tokenNumber < generatedTokenCount; tokenNumber++) {
            String token = tokenService.generateToken();

            assertTrue(token.matches("[A-Za-z0-9_-]{43}"), "Token should be unpadded URL-safe Base64.");
            tokens.add(token);
        }

        assertEquals(generatedTokenCount, tokens.size(), "Generated tokens should not collide in this sample.");
    }
}
