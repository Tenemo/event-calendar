package app.web;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;

public final class FacesMessages {
    private FacesMessages() {
    }

    public static void add(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (FacesMessage.SEVERITY_ERROR.equals(severity)) {
            facesContext.validationFailed();
        }
        facesContext.addMessage(null, new FacesMessage(severity, summary, detail));
    }
}
