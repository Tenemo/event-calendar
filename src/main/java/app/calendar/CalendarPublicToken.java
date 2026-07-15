package app.calendar;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CalendarPublicToken {
    public static final int RANDOM_BYTE_COUNT = 8;
    public static final int ENCODED_LENGTH = 11;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]");

    private CalendarPublicToken() {
    }

    public static String generate(SecureRandom secureRandom) {
        Objects.requireNonNull(secureRandom, "Secure random source is required.");
        byte[] tokenBytes = new byte[RANDOM_BYTE_COUNT];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    public static boolean isValid(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    public static String fromRequestPath(String contextPath, String requestUri) {
        if (contextPath == null || requestUri == null || !requestUri.startsWith(contextPath)) {
            return null;
        }
        String applicationPath = requestUri.substring(contextPath.length());
        if (applicationPath.length() != ENCODED_LENGTH + 1 || applicationPath.charAt(0) != '/') {
            return null;
        }
        String token = applicationPath.substring(1);
        return isValid(token) ? token : null;
    }
}
