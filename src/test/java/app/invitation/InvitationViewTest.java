package app.invitation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.util.ValidationException;
import org.junit.jupiter.api.Test;

final class InvitationViewTest {
    @Test
    void acceptsOnlyCanonicalPositiveInvitationIdentifiers() {
        assertEquals(42L, InvitationView.parseInvitationId("42"));

        assertAll(
                () -> assertThrows(ValidationException.class, () -> InvitationView.parseInvitationId(null)),
                () -> assertThrows(ValidationException.class, () -> InvitationView.parseInvitationId("")),
                () -> assertThrows(ValidationException.class, () -> InvitationView.parseInvitationId(" 42 ")),
                () -> assertThrows(ValidationException.class, () -> InvitationView.parseInvitationId("+42")),
                () -> assertThrows(ValidationException.class, () -> InvitationView.parseInvitationId("0")),
                () -> assertThrows(ValidationException.class, () -> InvitationView.parseInvitationId("-1")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> InvitationView.parseInvitationId("9999999999999999999")));
    }
}
