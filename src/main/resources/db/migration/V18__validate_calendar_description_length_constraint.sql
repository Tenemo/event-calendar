set local lock_timeout = '10s';
set local statement_timeout = '5min';

alter table calendar
    validate constraint calendar_description_maximum_length_check;
