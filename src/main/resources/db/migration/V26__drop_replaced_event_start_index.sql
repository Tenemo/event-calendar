set lock_timeout = '10s';
set statement_timeout = '5min';

drop index concurrently if exists idx_calendar_event_calendar_start;

reset statement_timeout;
reset lock_timeout;
