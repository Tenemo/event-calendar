set local lock_timeout = '10s';
set local statement_timeout = '5min';

alter table calendar_event
    validate constraint calendar_event_description_maximum_length_check;
