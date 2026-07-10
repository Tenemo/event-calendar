package app.util;

import jakarta.ejb.ApplicationException;

@ApplicationException(rollback = true)
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
