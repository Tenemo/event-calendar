set local lock_timeout = '10s';

alter table calendar
    validate constraint calendar_public_token_check_v13;
