alter table calendar_invitation
    rename to app_invitation;

alter index idx_calendar_invitation_calendar_id
    rename to idx_app_invitation_calendar_id;

alter table app_invitation
    alter column calendar_id drop not null,
    alter column role_name drop not null;

alter table app_invitation
    rename constraint calendar_invitation_pkey to app_invitation_pkey;

alter table app_invitation
    rename constraint calendar_invitation_invite_token_key to app_invitation_invite_token_key;

alter table app_invitation
    rename constraint calendar_invitation_calendar_id_fkey to app_invitation_calendar_id_fkey;

alter table app_invitation
    rename constraint calendar_invitation_created_by_user_id_fkey to app_invitation_created_by_user_id_fkey;

alter table app_invitation
    rename constraint calendar_invitation_accepted_by_user_id_fkey to app_invitation_accepted_by_user_id_fkey;

alter table app_invitation
    drop constraint calendar_invitation_role_name_check;

alter table app_invitation
    add constraint app_invitation_scope_check
        check (
            (calendar_id is null and role_name is null)
            or (calendar_id is not null and role_name in ('VIEWER', 'EDITOR'))
        );

insert into app_invitation (
    invite_token,
    calendar_id,
    role_name,
    created_by_user_id,
    accepted_by_user_id,
    revoked_at,
    accepted_at,
    expires_at,
    created_at
)
select
    invite_token,
    null,
    null,
    created_by_user_id,
    accepted_by_user_id,
    revoked_at,
    accepted_at,
    null,
    created_at
from app_registration_invitation
on conflict (invite_token) do nothing;

create index idx_app_invitation_created_by_user_id
    on app_invitation(created_by_user_id);

create index idx_app_invitation_accepted_by_user_id
    on app_invitation(accepted_by_user_id);

drop table app_registration_invitation;

alter table app_user
    drop column app_admin;
