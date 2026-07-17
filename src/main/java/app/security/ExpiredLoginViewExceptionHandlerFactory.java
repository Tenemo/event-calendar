package app.security;

import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerFactory;

public final class ExpiredLoginViewExceptionHandlerFactory extends ExceptionHandlerFactory {
    public ExpiredLoginViewExceptionHandlerFactory(ExceptionHandlerFactory wrappedFactory) {
        super(wrappedFactory);
    }

    @Override
    public ExceptionHandler getExceptionHandler() {
        return new ExpiredLoginViewExceptionHandler(getWrapped().getExceptionHandler());
    }
}
