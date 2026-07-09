package app.util;

import jakarta.ejb.ApplicationException;

@ApplicationException(rollback = true)
public class AuthorizationException extends RuntimeException {
    public AuthorizationException(String message) {
        super(message);
    }
}
