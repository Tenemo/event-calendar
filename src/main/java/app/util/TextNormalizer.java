package app.util;

public final class TextNormalizer {
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

    public static String normalizeOptionalText(String value) {
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
}
