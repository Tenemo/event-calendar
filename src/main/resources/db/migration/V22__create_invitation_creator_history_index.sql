set lock_timeout = '10s';
set statement_timeout = '5min';

drop index concurrently if exists idx_app_invitation_created_by_user_created_at_id;

create index concurrently idx_app_invitation_created_by_user_created_at_id
    on app_invitation(created_by_user_id, created_at desc, id desc);

reset statement_timeout;
reset lock_timeout;
