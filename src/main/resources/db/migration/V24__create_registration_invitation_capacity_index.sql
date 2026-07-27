set lock_timeout = '10s';
set statement_timeout = '5min';

drop index concurrently if exists idx_app_invitation_registration_creator_expires;

create index concurrently idx_app_invitation_registration_creator_expires
    on app_invitation(created_by_user_id, expires_at)
    where calendar_id is null
        and role_name is null
        and accepted_at is null
        and revoked_at is null;

reset statement_timeout;
reset lock_timeout;
