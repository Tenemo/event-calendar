package app.user;

import app.invitation.Invitation;

public record RegistrationAdmission(Invitation invitation, boolean bootstrap) {
}
