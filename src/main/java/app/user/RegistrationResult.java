package app.user;

import app.calendar.Calendar;

public record RegistrationResult(AppUser user, Calendar initialCalendar) {
}
