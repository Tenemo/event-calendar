package app.security;

import app.calendar.CalendarLinkToken;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class TokenService {
    private static final int INVITATION_TOKEN_BYTE_COUNT = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateInvitationToken() {
        byte[] tokenBytes = new byte[INVITATION_TOKEN_BYTE_COUNT];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    public String generateCalendarLinkToken() {
        return CalendarLinkToken.generate(secureRandom);
    }
}
