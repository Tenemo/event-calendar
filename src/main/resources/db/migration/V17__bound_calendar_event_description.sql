set local lock_timeout = '10s';
set local statement_timeout = '5min';

alter table calendar_event
    add constraint calendar_event_description_maximum_length_check
        check (
            description is null
            or char_length(description)
                + regexp_count(description, '[\U00010000-\U0010FFFF]') <= 4000
        ) not valid;
