package app.security;

import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerFactory;

public final class ExpiredSignInViewExceptionHandlerFactory extends ExceptionHandlerFactory {
    public ExpiredSignInViewExceptionHandlerFactory(ExceptionHandlerFactory wrappedFactory) {
        super(wrappedFactory);
    }

    @Override
    public ExceptionHandler getExceptionHandler() {
        return new ExpiredSignInViewExceptionHandler(getWrapped().getExceptionHandler());
    }
}
