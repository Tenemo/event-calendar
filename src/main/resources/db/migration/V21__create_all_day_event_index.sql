set lock_timeout = '10s';
set statement_timeout = '5min';

-- See V20 for why every concurrent build removes its own possibly invalid
-- predecessor before retrying.
drop index concurrently if exists idx_calendar_event_all_day_calendar;

create index concurrently idx_calendar_event_all_day_calendar
    on calendar_event(calendar_id)
    where all_day = true;

reset statement_timeout;
reset lock_timeout;
