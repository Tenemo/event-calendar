package app.security;

import app.calendar.CalendarPublicToken;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class TokenService {
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken() {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    public String generateCalendarPublicToken() {
        return CalendarPublicToken.generate(secureRandom);
    }
}
