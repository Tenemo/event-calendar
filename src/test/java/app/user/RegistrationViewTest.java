package app.user;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.invitation.InvitationAdmissionPreview;
import app.invitation.InvitationService;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.ExternalContextWrapper;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.FacesContextWrapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RegistrationViewTest {
    @Test
    void restoresTheGeneratedHiddenInvitationTokenBeforeRenderedChecks() {
        String invitationToken = "a".repeat(43);
        AtomicReference<String> previewedInvitationToken = new AtomicReference<>();
        RegistrationView registrationView = registrationView(previewedInvitationToken);
        RecordingFacesContext facesContext = new RecordingFacesContext(Map.of(
                "registrationForm:invitationToken",
                new String[] {invitationToken}), true);

        try {
            boolean invitationAvailable = registrationView.isInvitationAvailable();
            assertAll(
                    () -> assertTrue(invitationAvailable),
                    () -> assertEquals(invitationToken, registrationView.getInvitationToken()),
                    () -> assertEquals(invitationToken, previewedInvitationToken.get()));
        } finally {
            facesContext.release();
        }
    }

    @Test
    void rejectsConflictingInvitationParametersBeforeRenderedChecks() {
        String firstInvitationToken = "a".repeat(43);
        String secondInvitationToken = "b".repeat(43);
        AtomicReference<String> previewedInvitationToken = new AtomicReference<>();
        RegistrationView registrationView = registrationView(previewedInvitationToken);
        RecordingFacesContext facesContext = new RecordingFacesContext(Map.of(
                "token",
                new String[] {firstInvitationToken},
                "registrationForm:invitationToken",
                new String[] {secondInvitationToken}), true);

        try {
            boolean invitationAvailable = registrationView.isInvitationAvailable();
            assertAll(
                    () -> assertFalse(invitationAvailable),
                    () -> assertEquals("", registrationView.getInvitationToken()),
                    () -> assertEquals("", previewedInvitationToken.get()));
        } finally {
            facesContext.release();
        }
    }

    @Test
    void getRequestsRejectSubmittedComponentAliasesButAcceptTheCanonicalTokenParameter() {
        String invitationToken = "a".repeat(43);
        AtomicReference<String> aliasPreviewedInvitationToken = new AtomicReference<>();
        RegistrationView aliasRegistrationView = registrationView(aliasPreviewedInvitationToken);
        RecordingFacesContext aliasFacesContext = new RecordingFacesContext(Map.of(
                "registrationForm:invitationToken",
                new String[] {invitationToken}), false);

        boolean aliasInvitationAvailable;
        try {
            aliasInvitationAvailable = aliasRegistrationView.isInvitationAvailable();
        } finally {
            aliasFacesContext.release();
        }

        AtomicReference<String> canonicalPreviewedInvitationToken = new AtomicReference<>();
        RegistrationView canonicalRegistrationView = registrationView(
                canonicalPreviewedInvitationToken);
        RecordingFacesContext canonicalFacesContext = new RecordingFacesContext(Map.of(
                "token",
                new String[] {invitationToken}), false);
        boolean canonicalInvitationAvailable;
        try {
            canonicalInvitationAvailable = canonicalRegistrationView.isInvitationAvailable();
        } finally {
            canonicalFacesContext.release();
        }

        assertAll(
                () -> assertFalse(aliasInvitationAvailable),
                () -> assertEquals("", aliasPreviewedInvitationToken.get()),
                () -> assertTrue(canonicalInvitationAvailable),
                () -> assertEquals(invitationToken, canonicalPreviewedInvitationToken.get()));
    }

    private static RegistrationView registrationView(
            AtomicReference<String> previewedInvitationToken) {
        RegistrationView registrationView = new RegistrationView();
        setField(registrationView, "invitationService", new InvitationService() {
            @Override
            public InvitationAdmissionPreview previewAdmission(String invitationToken) {
                previewedInvitationToken.set(invitationToken);
                return invitationToken.isEmpty()
                        ? InvitationAdmissionPreview.unavailable()
                        : InvitationAdmissionPreview.registration(null);
            }
        });
        return registrationView;
    }

    private static final class RecordingFacesContext extends FacesContextWrapper {
        private final ExternalContext externalContext;
        private final boolean postback;

        private RecordingFacesContext(
                Map<String, String[]> requestParameterValues,
                boolean postback) {
            super(null);
            externalContext = new RecordingExternalContext(requestParameterValues);
            this.postback = postback;
            setCurrentInstance(this);
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
        public boolean isPostback() {
            return postback;
        }

        @Override
        public void release() {
            setCurrentInstance(null);
        }
    }

    private static final class RecordingExternalContext extends ExternalContextWrapper {
        private final Map<String, String[]> requestParameterValues;

        private RecordingExternalContext(Map<String, String[]> requestParameterValues) {
            super(null);
            this.requestParameterValues = requestParameterValues;
        }

        @Override
        public ExternalContext getWrapped() {
            return null;
        }

        @Override
        public Map<String, String> getInitParameterMap() {
            return Map.of();
        }

        @Override
        public Map<String, String[]> getRequestParameterValuesMap() {
            return requestParameterValues;
        }
    }
}
