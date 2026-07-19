package app.web;

import java.util.OptionalLong;

public final class ViewParameterParser {
    private ViewParameterParser() {
    }

    public static OptionalLong positiveLong(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 19
                || !value.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        try {
            long parsedValue = Long.parseLong(value);
            return parsedValue > 0
                    ? OptionalLong.of(parsedValue)
                    : OptionalLong.empty();
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }
}
