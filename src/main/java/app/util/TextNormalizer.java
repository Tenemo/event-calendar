package app.util;

public final class TextNormalizer {
    /**
     * Calendar and event descriptions share this boundary so one calendar page has a predictable
     * upper limit. The value is measured in UTF-16 code units to match both {@link String#length()}
     * and the browser's HTML {@code maxlength} enforcement.
     */
    public static final int MAXIMUM_DESCRIPTION_LENGTH = 4_000;

    private TextNormalizer() {
    }

    public static String normalizeRequiredText(
            String value,
            String blankMessage,
            int maximumLength,
            String lengthMessage) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(blankMessage);
        }
        String normalizedValue = value.trim();
        if (normalizedValue.length() > maximumLength) {
            throw new ValidationException(lengthMessage);
        }
        return normalizedValue;
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static String normalizeOptionalText(
            String value,
            int maximumLength,
            String lengthMessage) {
        String normalizedValue = normalizeOptionalText(value);
        if (normalizedValue != null && normalizedValue.length() > maximumLength) {
            throw new ValidationException(lengthMessage);
        }
        return normalizedValue;
    }

    public static String normalizeOptionalMultilineText(
            String value,
            int maximumLength,
            String lengthMessage) {
        String normalizedLineEndings = value == null
                ? null
                : value.replace("\r\n", "\n").replace('\r', '\n');
        return normalizeOptionalText(normalizedLineEndings, maximumLength, lengthMessage);
    }
}
