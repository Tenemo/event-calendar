set lock_timeout = '10s';
set statement_timeout = '5min';

drop index concurrently if exists idx_app_invitation_created_by_user_id;

reset statement_timeout;
reset lock_timeout;
