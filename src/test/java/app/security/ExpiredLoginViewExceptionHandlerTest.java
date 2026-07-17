package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.faces.FacesException;
import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.ExternalContextWrapper;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.FacesContextWrapper;
import jakarta.faces.event.AbortProcessingException;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.faces.event.ExceptionQueuedEventContext;
import jakarta.faces.event.PhaseId;
import jakarta.faces.event.SystemEvent;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

final class ExpiredLoginViewExceptionHandlerTest {
    private static final Path FACES_CONFIGURATION_PATH =
            Path.of("src", "main", "webapp", "WEB-INF", "faces-config.xml");

    @Test
    void handleRemovesEveryRecoverableEventAndWritesOneRelativeRedirect() {
        ResponseRecorder responseRecorder = new ResponseRecorder(false);
        RecordingFacesContext facesContext = facesContext(
                request("POST", "/calendar", "/calendar/login"),
                responseRecorder.response(),
                "/calendar");
        ExceptionQueuedEvent firstRecoverableEvent = event(
                facesContext,
                new ViewExpiredException("The first sign-in view expired.", "/login"));
        ExceptionQueuedEvent secondRecoverableEvent = event(
                facesContext,
                new ServletException(
                        "The second Faces postback failed.",
                        new ViewExpiredException(
                                "The second sign-in view expired.", "/login.xhtml")));
        ExceptionQueuedEvent nonrecoverableEvent =
                event(facesContext, new IllegalStateException("Database failed."));
        RecordingExceptionHandler wrappedHandler = new RecordingExceptionHandler(
                firstRecoverableEvent, nonrecoverableEvent, secondRecoverableEvent);

        new ExpiredLoginViewExceptionHandler(wrappedHandler).handle();

        List<ExceptionQueuedEvent> expectedDelegatedEvents = List.of(nonrecoverableEvent);
        assertAll(
                () -> assertIterableEquals(
                        expectedDelegatedEvents,
                        wrappedHandler.getUnhandledExceptionQueuedEvents()),
                () -> assertIterableEquals(
                        expectedDelegatedEvents, wrappedHandler.eventsObservedDuringHandle()),
                () -> assertEquals(1, wrappedHandler.handleCallCount()),
                () -> assertEquals(1, responseRecorder.resetBufferCallCount()),
                () -> assertEquals(1, responseRecorder.statusWriteCount()),
                () -> assertEquals(HttpServletResponse.SC_FOUND, responseRecorder.status()),
                () -> assertEquals(1, responseRecorder.headerWriteCount("Location")),
                () -> assertEquals(
                        "/calendar/login?reauthenticationRequired=true",
                        responseRecorder.lastHeaderValue("Location")),
                () -> assertEquals(1, responseRecorder.headerWriteCount("Cache-Control")),
                () -> assertEquals(
                        "no-store", responseRecorder.lastHeaderValue("Cache-Control")),
                () -> assertEquals(1, facesContext.responseCompleteCallCount()));
    }

    @Test
    void handleLeavesNonrecoverableEventsForTheWrappedHandler() {
        ResponseRecorder getResponseRecorder = new ResponseRecorder(false);
        RecordingFacesContext getFacesContext = facesContext(
                request("GET", "", "/login"), getResponseRecorder.response(), "");
        ExceptionQueuedEvent getEvent = event(
                getFacesContext,
                new ViewExpiredException("A non-postback view expired.", "/login"));

        ResponseRecorder committedResponseRecorder = new ResponseRecorder(true);
        RecordingFacesContext committedFacesContext = facesContext(
                request("POST", "", "/login"), committedResponseRecorder.response(), "");
        ExceptionQueuedEvent committedEvent = event(
                committedFacesContext,
                new ViewExpiredException("A committed view expired.", "/login"));

        ResponseRecorder otherViewResponseRecorder = new ResponseRecorder(false);
        RecordingFacesContext otherViewFacesContext = facesContext(
                request("POST", "", "/register"), otherViewResponseRecorder.response(), "");
        ExceptionQueuedEvent otherViewEvent = event(
                otherViewFacesContext,
                new ViewExpiredException("The registration view expired.", "/register"));

        List<ExceptionQueuedEvent> nonrecoverableEvents =
                List.of(getEvent, committedEvent, otherViewEvent);
        RecordingExceptionHandler wrappedHandler =
                new RecordingExceptionHandler(nonrecoverableEvents.toArray(ExceptionQueuedEvent[]::new));

        new ExpiredLoginViewExceptionHandler(wrappedHandler).handle();

        assertAll(
                () -> assertIterableEquals(
                        nonrecoverableEvents,
                        wrappedHandler.getUnhandledExceptionQueuedEvents()),
                () -> assertIterableEquals(
                        nonrecoverableEvents, wrappedHandler.eventsObservedDuringHandle()),
                () -> assertEquals(1, wrappedHandler.handleCallCount()),
                () -> assertEquals(0, getResponseRecorder.responseWriteCount()),
                () -> assertEquals(0, committedResponseRecorder.responseWriteCount()),
                () -> assertEquals(0, otherViewResponseRecorder.responseWriteCount()),
                () -> assertEquals(0, getFacesContext.responseCompleteCallCount()),
                () -> assertEquals(0, committedFacesContext.responseCompleteCallCount()),
                () -> assertEquals(0, otherViewFacesContext.responseCompleteCallCount()));
    }

    @Test
    void recoversOnlyUncommittedExpiredSignInPostbacks() {
        Throwable wrappedExpiredLoginView = new ServletException(
                "Faces postback failed.",
                new ViewExpiredException("The view expired.", "/login"));

        assertAll(
                () -> assertTrue(ExpiredLoginViewExceptionHandler.isRecoverableExpiredLoginPostback(
                        request("POST", "", "/login"),
                        response(false),
                        wrappedExpiredLoginView)),
                () -> assertTrue(ExpiredLoginViewExceptionHandler.isRecoverableExpiredLoginPostback(
                        request("POST", "/calendar", "/calendar/login.xhtml"),
                        response(false),
                        new ViewExpiredException("The view expired.", "/login.xhtml"))),
                () -> assertFalse(ExpiredLoginViewExceptionHandler.isRecoverableExpiredLoginPostback(
                        request("GET", "", "/login"),
                        response(false),
                        wrappedExpiredLoginView)),
                () -> assertFalse(ExpiredLoginViewExceptionHandler.isRecoverableExpiredLoginPostback(
                        request("POST", "", "/register"),
                        response(false),
                        wrappedExpiredLoginView)),
                () -> assertFalse(ExpiredLoginViewExceptionHandler.isRecoverableExpiredLoginPostback(
                        request("POST", "", "/login"),
                        response(true),
                        wrappedExpiredLoginView)),
                () -> assertFalse(ExpiredLoginViewExceptionHandler.isRecoverableExpiredLoginPostback(
                        request("POST", "", "/login"),
                        response(false),
                        new ViewExpiredException("Another view expired.", "/register"))),
                () -> assertFalse(ExpiredLoginViewExceptionHandler.isRecoverableExpiredLoginPostback(
                        request("POST", "", "/login"),
                        response(false),
                        new IllegalStateException("Database failed."))));
    }

    @Test
    void facesConfigurationRegistersTheRecoveryFactory() throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList factoryElements = documentBuilderFactory
                .newDocumentBuilder()
                .parse(FACES_CONFIGURATION_PATH.toFile())
                .getElementsByTagName("exception-handler-factory");

        assertAll(
                () -> assertEquals(1, factoryElements.getLength()),
                () -> assertEquals(
                        ExpiredLoginViewExceptionHandlerFactory.class.getName(),
                        factoryElements.item(0).getTextContent().trim()));
    }

    private static HttpServletRequest request(
            String method,
            String contextPath,
            String requestUri) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (ignoredProxy, invokedMethod, ignoredArguments) -> switch (invokedMethod.getName()) {
                    case "getMethod" -> method;
                    case "getContextPath" -> contextPath;
                    case "getRequestURI" -> requestUri;
                    default -> defaultValue(invokedMethod.getReturnType());
                });
    }

    private static HttpServletResponse response(boolean committed) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (ignoredProxy, invokedMethod, ignoredArguments) ->
                        invokedMethod.getName().equals("isCommitted")
                                ? committed
                                : defaultValue(invokedMethod.getReturnType()));
    }

    private static RecordingFacesContext facesContext(
            HttpServletRequest request,
            HttpServletResponse response,
            String requestContextPath) {
        return new RecordingFacesContext(
                new RecordingExternalContext(request, response, requestContextPath));
    }

    private static ExceptionQueuedEvent event(FacesContext facesContext, Throwable exception) {
        return new ExceptionQueuedEvent(new ExceptionQueuedEventContext(facesContext, exception));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0F;
        }
        if (returnType == double.class) {
            return 0.0D;
        }
        throw new IllegalArgumentException("Unsupported primitive return type: " + returnType);
    }

    private static final class RecordingExceptionHandler extends ExceptionHandler {
        private final List<ExceptionQueuedEvent> unhandledEvents;
        private List<ExceptionQueuedEvent> eventsObservedDuringHandle = List.of();
        private int handleCallCount;

        private RecordingExceptionHandler(ExceptionQueuedEvent... unhandledEvents) {
            this.unhandledEvents = new ArrayList<>(List.of(unhandledEvents));
        }

        @Override
        public void handle() throws FacesException {
            handleCallCount++;
            eventsObservedDuringHandle = List.copyOf(unhandledEvents);
        }

        @Override
        public ExceptionQueuedEvent getHandledExceptionQueuedEvent() {
            return null;
        }

        @Override
        public Iterable<ExceptionQueuedEvent> getUnhandledExceptionQueuedEvents() {
            return unhandledEvents;
        }

        @Override
        public Iterable<ExceptionQueuedEvent> getHandledExceptionQueuedEvents() {
            return List.of();
        }

        @Override
        public void processEvent(SystemEvent event) throws AbortProcessingException {
            // The recovery handler does not publish events to its wrapped handler.
        }

        @Override
        public boolean isListenerForSource(Object source) {
            return false;
        }

        @Override
        public Throwable getRootCause(Throwable throwable) {
            return throwable;
        }

        private List<ExceptionQueuedEvent> eventsObservedDuringHandle() {
            return eventsObservedDuringHandle;
        }

        private int handleCallCount() {
            return handleCallCount;
        }
    }

    private static final class RecordingFacesContext extends FacesContextWrapper {
        private final ExternalContext externalContext;
        private int responseCompleteCallCount;

        private RecordingFacesContext(ExternalContext externalContext) {
            super(null);
            this.externalContext = externalContext;
        }

        @Override
        public FacesContext getWrapped() {
            return null;
        }

        @Override
        public ExternalContext getExternalContext() {
            return externalContext;
        }

        @Override
        public PhaseId getCurrentPhaseId() {
            return PhaseId.ANY_PHASE;
        }

        @Override
        public void responseComplete() {
            responseCompleteCallCount++;
        }

        private int responseCompleteCallCount() {
            return responseCompleteCallCount;
        }
    }

    private static final class RecordingExternalContext extends ExternalContextWrapper {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String requestContextPath;

        private RecordingExternalContext(
                HttpServletRequest request,
                HttpServletResponse response,
                String requestContextPath) {
            super(null);
            this.request = request;
            this.response = response;
            this.requestContextPath = requestContextPath;
        }

        @Override
        public ExternalContext getWrapped() {
            return null;
        }

        @Override
        public Object getRequest() {
            return request;
        }

        @Override
        public Object getResponse() {
            return response;
        }

        @Override
        public Map<String, String> getInitParameterMap() {
            return Map.of();
        }

        @Override
        public String getRequestContextPath() {
            return requestContextPath;
        }
    }

    private static final class ResponseRecorder {
        private final List<HeaderWrite> headerWrites = new ArrayList<>();
        private final HttpServletResponse response;
        private int resetBufferCallCount;
        private int statusWriteCount;
        private int status;

        private ResponseRecorder(boolean committed) {
            response = (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class},
                    (ignoredProxy, invokedMethod, arguments) -> {
                        switch (invokedMethod.getName()) {
                            case "isCommitted" -> {
                                return committed;
                            }
                            case "resetBuffer" -> resetBufferCallCount++;
                            case "setStatus" -> {
                                statusWriteCount++;
                                status = (int) arguments[0];
                            }
                            case "setHeader" -> headerWrites.add(
                                    new HeaderWrite((String) arguments[0], (String) arguments[1]));
                            default -> {
                                return defaultValue(invokedMethod.getReturnType());
                            }
                        }
                        return null;
                    });
        }

        private HttpServletResponse response() {
            return response;
        }

        private int resetBufferCallCount() {
            return resetBufferCallCount;
        }

        private int statusWriteCount() {
            return statusWriteCount;
        }

        private int status() {
            return status;
        }

        private int headerWriteCount(String headerName) {
            return Math.toIntExact(headerWrites.stream()
                    .filter(headerWrite -> headerWrite.name().equals(headerName))
                    .count());
        }

        private String lastHeaderValue(String headerName) {
            return headerWrites.stream()
                    .filter(headerWrite -> headerWrite.name().equals(headerName))
                    .reduce((firstHeaderWrite, secondHeaderWrite) -> secondHeaderWrite)
                    .map(HeaderWrite::value)
                    .orElse(null);
        }

        private int responseWriteCount() {
            return resetBufferCallCount + statusWriteCount + headerWrites.size();
        }
    }

    private record HeaderWrite(String name, String value) {}
}
