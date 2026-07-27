set lock_timeout = '10s';
set statement_timeout = '5min';

-- A failed concurrent build can leave an invalid index with this name. After
-- repairing its failed Flyway history row, removing it first makes this safe to retry.
drop index concurrently if exists idx_calendar_event_calendar_start_id;

create index concurrently idx_calendar_event_calendar_start_id
    on calendar_event(calendar_id, start_at, id);

reset statement_timeout;
reset lock_timeout;
