package app.web;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.FacesContextWrapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FacesMessagesTest {
    @Test
    void errorMessagesMarkTheRequestAsFailedWhileInformationalMessagesDoNot() {
        RecordingFacesContext facesContext = new RecordingFacesContext();
        try {
            FacesMessages.add(FacesMessage.SEVERITY_INFO, "Saved.", "The change was saved.");
            assertFalse(facesContext.isValidationFailed());

            FacesMessages.add(FacesMessage.SEVERITY_ERROR, "Not saved.", "Correct the error.");

            assertAll(
                    () -> assertTrue(facesContext.isValidationFailed()),
                    () -> assertEquals(2, facesContext.messages.size()),
                    () -> assertEquals(
                            FacesMessage.SEVERITY_ERROR,
                            facesContext.messages.get(1).getSeverity()),
                    () -> assertEquals("Not saved.", facesContext.messages.get(1).getSummary()),
                    () -> assertEquals("Correct the error.", facesContext.messages.get(1).getDetail()));
        } finally {
            facesContext.release();
        }
    }

    private static final class RecordingFacesContext extends FacesContextWrapper {
        private final List<FacesMessage> messages = new ArrayList<>();
        private boolean validationFailed;

        private RecordingFacesContext() {
            super(null);
            setCurrentInstance(this);
        }

        @Override
        public FacesContext getWrapped() {
            return null;
        }

        @Override
        public void addMessage(String clientId, FacesMessage message) {
            messages.add(message);
        }

        @Override
        public void validationFailed() {
            validationFailed = true;
        }

        @Override
        public boolean isValidationFailed() {
            return validationFailed;
        }

        @Override
        public void release() {
            setCurrentInstance(null);
        }
    }
}
