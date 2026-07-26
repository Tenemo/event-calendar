set local lock_timeout = '10s';

alter table calendar
    drop constraint if exists calendar_public_token_check;

alter table calendar
    rename constraint calendar_public_token_check_v13 to calendar_public_token_check;
