package app.web;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ResponseWriter;
import jakarta.faces.context.ResponseWriterWrapper;
import java.io.IOException;
import org.primefaces.component.messages.Messages;
import org.primefaces.component.messages.MessagesRenderer;

public final class AccessibleMessagesRenderer extends MessagesRenderer {
    @Override
    protected void encodeMessage(
            ResponseWriter responseWriter,
            Messages messages,
            FacesMessage message,
            String severityStyleClass,
            boolean escape) throws IOException {
        super.encodeMessage(
                new ListItemRoleResponseWriter(responseWriter),
                messages,
                message,
                severityStyleClass,
                escape);
    }

    private static final class ListItemRoleResponseWriter extends ResponseWriterWrapper {
        private ListItemRoleResponseWriter(ResponseWriter responseWriter) {
            super(responseWriter);
        }

        @Override
        public void writeAttribute(
                String name,
                Object value,
                String property) throws IOException {
            Object accessibleValue = "role".equals(name) && "alert".equals(value)
                    ? "listitem"
                    : value;
            super.writeAttribute(name, accessibleValue, property);
        }
    }
}
