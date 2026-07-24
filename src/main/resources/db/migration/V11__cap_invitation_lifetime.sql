alter table app_invitation
    add constraint app_invitation_maximum_lifetime_check
        check (expires_at <= created_at + interval '7 days') not valid;

update app_invitation
set expires_at = created_at + interval '7 days'
where expires_at > created_at + interval '7 days';

alter table app_invitation
    validate constraint app_invitation_maximum_lifetime_check;
