package app.security;

import app.calendar.CalendarLinkToken;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

@ApplicationScoped
public class TokenService {
    private static final int TOKEN_BYTE_COUNT = 32;
    static final String API_TOKEN_PREFIX = "calendar_social_api_";

    private final SecureRandom secureRandom;

    public TokenService() {
        this(new SecureRandom());
    }

    TokenService(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "Secure random source is required.");
    }

    public String generateInvitationToken() {
        return generateRandomToken();
    }

    public String generateApiToken() {
        return API_TOKEN_PREFIX + generateRandomToken();
    }

    private String generateRandomToken() {
        byte[] tokenBytes = new byte[TOKEN_BYTE_COUNT];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    public String generateCalendarLinkToken() {
        return CalendarLinkToken.generate(secureRandom);
    }
}
