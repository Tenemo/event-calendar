package app.api;

import app.event.EventTimeInput;
import app.util.ValidationException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

final class ApiJson {
    private ApiJson() {
    }

    static JsonObject requireBody(JsonObject body) {
        if (body == null) {
            throw new ValidationException("Request body is required.");
        }
        return body;
    }

    static String requireString(JsonObject body, String propertyName) {
        JsonValue value = requireBody(body).get(propertyName);
        if (!(value instanceof JsonString stringValue)) {
            throw new ValidationException(propertyName + " must be a string.");
        }
        return stringValue.getString();
    }

    static String optionalString(JsonObject body, String propertyName) {
        JsonValue value = requireBody(body).get(propertyName);
        if (value == null || value == JsonValue.NULL) {
            return null;
        }
        if (!(value instanceof JsonString stringValue)) {
            throw new ValidationException(propertyName + " must be a string or null.");
        }
        return stringValue.getString();
    }

    static boolean requireBoolean(JsonObject body, String propertyName) {
        JsonValue value = requireBody(body).get(propertyName);
        if (value == JsonValue.TRUE) {
            return true;
        }
        if (value == JsonValue.FALSE) {
            return false;
        }
        throw new ValidationException(propertyName + " must be a boolean.");
    }

    static JsonObject requireObject(JsonObject body, String propertyName) {
        JsonValue value = requireBody(body).get(propertyName);
        if (!(value instanceof JsonObject objectValue)) {
            throw new ValidationException(propertyName + " must be an object.");
        }
        return objectValue;
    }

    static EventTimeInput requireEventTime(JsonObject body) {
        JsonObject time = requireObject(body, "time");
        String kind = requireString(time, "kind");
        return switch (kind) {
            case "timed" -> new EventTimeInput.Timed(
                    requireLocalDateTime(time, "start"),
                    requireLocalDateTime(time, "end"));
            case "all-day" -> new EventTimeInput.AllDay(
                    requireLocalDate(time, "firstDay"),
                    requireLocalDate(time, "lastDay"));
            default -> throw new ValidationException("time.kind must be timed or all-day.");
        };
    }

    private static LocalDate requireLocalDate(JsonObject body, String propertyName) {
        return parseLocalDate(requireString(body, propertyName), propertyName);
    }

    static LocalDate parseLocalDate(String value, String propertyName) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ValidationException(propertyName + " must be an ISO 8601 calendar date.");
        }
    }

    private static LocalDateTime requireLocalDateTime(JsonObject body, String propertyName) {
        return parseLocalDateTime(requireString(body, propertyName), propertyName);
    }

    static LocalDateTime parseLocalDateTime(String value, String propertyName) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ValidationException(propertyName + " must be an ISO 8601 local date and time.");
        }
    }
}
