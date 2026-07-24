package app.endtoend;

import java.net.URI;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BearerSecretRedactor {
    private static final String REDACTED_BEARER_VALUE = "[redacted]";
    private static final String REDACTED_CALENDAR_TOKEN = "[calendar-token]";
    private static final Pattern BEARER_QUERY_PARAMETER = Pattern.compile(
            "(?i)([?&](?:token|invite)=)([^\\s&#\\\"'<>),.;:]+)");
    private static final Pattern CANONICAL_CALENDAR_PATH = Pattern.compile(
            "/([A-Za-z0-9_-]{10}[AEIMQUYcgkosw048])(?=$|[/?#\\s\\\"'<>),.;:])");

    private final Set<String> rememberedBearerValues = ConcurrentHashMap.newKeySet();

    void rememberBearerValue(String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        String normalizedValue = value.trim();
        boolean extractedBearerValue = false;

        Matcher queryParameterMatcher = BEARER_QUERY_PARAMETER.matcher(normalizedValue);
        while (queryParameterMatcher.find()) {
            rememberExtractedValue(queryParameterMatcher.group(2));
            extractedBearerValue = true;
        }

        Matcher calendarPathMatcher = CANONICAL_CALENDAR_PATH.matcher(normalizedValue);
        while (calendarPathMatcher.find()) {
            rememberExtractedValue(calendarPathMatcher.group(1));
            extractedBearerValue = true;
        }

        if (!extractedBearerValue) {
            rememberExtractedValue(normalizedValue);
        }
    }

    String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String redactedText = text;
        for (String rememberedBearerValue : rememberedBearerValues.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
            redactedText = redactedText.replace(rememberedBearerValue, REDACTED_BEARER_VALUE);
        }
        redactedText = BEARER_QUERY_PARAMETER
                .matcher(redactedText)
                .replaceAll("$1" + REDACTED_BEARER_VALUE);
        return CANONICAL_CALENDAR_PATH
                .matcher(redactedText)
                .replaceAll("/" + REDACTED_CALENDAR_TOKEN);
    }

    String redactUrl(String value) {
        if (value == null || value.isBlank()) {
            return "[unparseable URL]";
        }

        try {
            URI uri = URI.create(value);
            StringBuilder redactedUrl = new StringBuilder();
            if (uri.getScheme() != null) {
                redactedUrl.append(uri.getScheme()).append("://");
            }
            if (uri.getRawAuthority() != null) {
                redactedUrl.append(uri.getRawAuthority());
            }
            String path = uri.getRawPath();
            redactedUrl.append(path == null || path.isBlank() ? "/" : redact(path));
            if (uri.getRawQuery() != null) {
                redactedUrl.append("?[redacted]");
            }
            return redact(redactedUrl.toString());
        } catch (IllegalArgumentException exception) {
            return redact(value);
        }
    }

    private void rememberExtractedValue(String value) {
        if (value.length() >= 4 && !value.equals(REDACTED_BEARER_VALUE)) {
            rememberedBearerValues.add(value);
        }
    }
}
