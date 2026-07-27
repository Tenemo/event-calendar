package app.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

@ApplicationScoped
public class AuthenticationAuditService {
    static final int MAXIMUM_DIRECT_EVENTS_PER_TYPE_AND_WINDOW = 10;
    static final Duration AUDIT_RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

    private static final Logger LOGGER = Logger.getLogger(
            AuthenticationAuditService.class.getName());

    private final Clock clock;
    private final Consumer<String> auditEventSink;
    private final EnumMap<AuthenticationEvent, EventWindow> eventWindows =
            new EnumMap<>(AuthenticationEvent.class);

    public AuthenticationAuditService() {
        this(Clock.systemUTC(), LOGGER::info);
    }

    AuthenticationAuditService(Clock clock, Consumer<String> auditEventSink) {
        this.clock = clock;
        this.auditEventSink = auditEventSink;
    }

    static AuthenticationAuditService noOperation() {
        return new AuthenticationAuditService(Clock.systemUTC(), ignoredEvent -> { });
    }

    public void recordSignInSucceeded() {
        record(AuthenticationEvent.SIGN_IN_SUCCEEDED);
    }

    public void recordSignInFailed() {
        record(AuthenticationEvent.SIGN_IN_FAILED);
    }

    public void recordSignInThrottled() {
        record(AuthenticationEvent.SIGN_IN_THROTTLED);
    }

    public void recordSignOut() {
        record(AuthenticationEvent.SIGN_OUT);
    }

    public void recordForcedSessionInvalidation() {
        record(AuthenticationEvent.FORCED_SESSION_INVALIDATION);
    }

    private synchronized void record(AuthenticationEvent authenticationEvent) {
        Instant now = clock.instant();
        EventWindow eventWindow = eventWindows.computeIfAbsent(
                authenticationEvent,
                ignoredEvent -> new EventWindow(now));
        if (!now.isBefore(eventWindow.windowStartedAt.plus(AUDIT_RATE_LIMIT_WINDOW))) {
            emitSuppressedEventSummary(authenticationEvent, eventWindow);
            eventWindow = new EventWindow(now);
            eventWindows.put(authenticationEvent, eventWindow);
        }

        if (eventWindow.directEventCount < MAXIMUM_DIRECT_EVENTS_PER_TYPE_AND_WINDOW) {
            auditEventSink.accept(message(authenticationEvent, "recorded"));
            eventWindow.directEventCount++;
            return;
        }

        eventWindow.suppressedEventCount++;
        if (!eventWindow.suppressionNoticeEmitted) {
            auditEventSink.accept(message(authenticationEvent, "further_events_suppressed"));
            eventWindow.suppressionNoticeEmitted = true;
        }
    }

    private void emitSuppressedEventSummary(
            AuthenticationEvent authenticationEvent,
            EventWindow eventWindow) {
        if (eventWindow.suppressedEventCount > 0) {
            auditEventSink.accept(
                    message(authenticationEvent, "suppressed_count="
                            + eventWindow.suppressedEventCount));
        }
    }

    private String message(AuthenticationEvent authenticationEvent, String outcome) {
        return "authentication_audit event="
                + authenticationEvent.eventName
                + " outcome="
                + outcome;
    }

    private enum AuthenticationEvent {
        SIGN_IN_SUCCEEDED("sign_in_succeeded"),
        SIGN_IN_FAILED("sign_in_failed"),
        SIGN_IN_THROTTLED("sign_in_throttled"),
        SIGN_OUT("sign_out"),
        FORCED_SESSION_INVALIDATION("forced_session_invalidation");

        private final String eventName;

        AuthenticationEvent(String eventName) {
            this.eventName = eventName;
        }
    }

    private static final class EventWindow {
        private final Instant windowStartedAt;
        private int directEventCount;
        private int suppressedEventCount;
        private boolean suppressionNoticeEmitted;

        private EventWindow(Instant windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
