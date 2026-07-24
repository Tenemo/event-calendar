with all_day_event_dates as (
    select calendar_event.id,
           calendar.timezone,
           (calendar_event.start_at at time zone calendar.timezone)::date as first_day,
           greatest(
                   (calendar_event.start_at at time zone calendar.timezone)::date,
                   (calendar_event.end_at at time zone calendar.timezone)::date
           ) as last_day
    from calendar_event
    join calendar on calendar.id = calendar_event.calendar_id
    join pg_timezone_names postgres_timezone on postgres_timezone.name = calendar.timezone
    where calendar_event.all_day = true
)
update calendar_event
set start_at = all_day_event_dates.first_day::timestamp at time zone all_day_event_dates.timezone,
    end_at = (all_day_event_dates.last_day + 1)::timestamp at time zone all_day_event_dates.timezone
from all_day_event_dates
where calendar_event.id = all_day_event_dates.id;
