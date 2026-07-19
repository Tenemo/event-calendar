alter table app_invitation
    drop constraint app_invitation_scope_check;

alter table app_invitation
    rename constraint calendar_invitation_invite_token_check to app_invitation_invite_token_check;

update app_invitation
set calendar_id = null,
    role_name = null
where role_name = 'VIEWER';

alter table app_invitation
    add constraint app_invitation_scope_check
        check (
            (calendar_id is null and role_name is null)
            or (calendar_id is not null and role_name = 'EDITOR')
        );
