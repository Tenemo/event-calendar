package app.web;

import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerFactory;

public final class ExpiredViewExceptionHandlerFactory extends ExceptionHandlerFactory {
    public ExpiredViewExceptionHandlerFactory(ExceptionHandlerFactory wrappedFactory) {
        super(wrappedFactory);
    }

    @Override
    public ExceptionHandler getExceptionHandler() {
        return new ExpiredViewExceptionHandler(getWrapped().getExceptionHandler());
    }
}
