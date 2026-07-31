package app.calendar;

import app.event.CalendarEventItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import org.primefaces.model.DefaultScheduleEvent;
import org.primefaces.model.DefaultScheduleModel;
import org.primefaces.model.ScheduleModel;

final class CalendarScheduleModelFactory {
    private CalendarScheduleModelFactory() {
    }

    static ScheduleModel create(List<CalendarEventItem> events) {
        DefaultScheduleModel scheduleModel = new DefaultScheduleModel();
        scheduleModel.setEventLimit(true);
        for (CalendarEventItem event : events) {
            DefaultScheduleEvent.Builder<Long> scheduleEvent = DefaultScheduleEvent.<Long>builder()
                    .title(event.getTitle())
                    .startDate(scheduleStartDate(event))
                    .endDate(scheduleEndDate(event))
                    .allDay(event.isAllDay())
                    .description(scheduleDescription(event))
                    .data(event.getId())
                    .editable(false)
                    .draggable(false)
                    .resizable(false)
                    .styleClass(event.isAllDay()
                            ? "calendar-event calendar-event-all-day"
                            : "calendar-event calendar-event-timed");
            if (event.getId() != null) {
                scheduleEvent.id(event.getId().toString());
            }
            scheduleModel.addEvent(scheduleEvent.build());
        }
        return scheduleModel;
    }

    static LocalDate findInitialDate(List<CalendarEventItem> events, LocalDate today) {
        if (events.isEmpty()) {
            return today;
        }

        YearMonth currentMonth = YearMonth.from(today);
        if (events.stream()
                .map(CalendarEventItem::getStartTime)
                .map(LocalDateTime::toLocalDate)
                .map(YearMonth::from)
                .anyMatch(currentMonth::equals)) {
            return today;
        }

        return events.stream()
                .map(CalendarEventItem::getStartTime)
                .map(LocalDateTime::toLocalDate)
                .filter(eventDate -> !eventDate.isBefore(today))
                .min(Comparator.naturalOrder())
                .orElseGet(() -> events.stream()
                        .map(CalendarEventItem::getStartTime)
                        .map(LocalDateTime::toLocalDate)
                        .max(Comparator.naturalOrder())
                        .orElse(today));
    }

    private static LocalDateTime scheduleEndDate(CalendarEventItem event) {
        if (!event.isAllDay()) {
            return event.getEndTime();
        }
        return event.getInclusiveEndDate().plusDays(1).atStartOfDay();
    }

    private static LocalDateTime scheduleStartDate(CalendarEventItem event) {
        if (!event.isAllDay()) {
            return event.getStartTime();
        }
        return event.getStartTime().toLocalDate().atStartOfDay();
    }

    private static String scheduleDescription(CalendarEventItem event) {
        String scheduleAndLocation = event.getScheduleAndLocationLabel();
        if (event.getDescription() == null || event.getDescription().isBlank()) {
            return scheduleAndLocation;
        }
        return scheduleAndLocation + "\n" + event.getDescription();
    }
}
