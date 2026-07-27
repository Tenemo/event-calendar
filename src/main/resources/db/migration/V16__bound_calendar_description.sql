set local lock_timeout = '10s';
set local statement_timeout = '5min';

alter table calendar
    add constraint calendar_description_maximum_length_check
        check (
            description is null
            or char_length(description)
                + regexp_count(description, '[\U00010000-\U0010FFFF]') <= 4000
        ) not valid;
