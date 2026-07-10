package app.user;

import app.invitation.AppInvitation;

public record RegistrationAdmission(AppInvitation invitation, boolean bootstrap) {
}
