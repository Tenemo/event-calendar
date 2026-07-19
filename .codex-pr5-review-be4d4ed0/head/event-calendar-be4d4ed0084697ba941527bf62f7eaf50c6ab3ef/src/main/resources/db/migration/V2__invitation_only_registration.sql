alter table app_user
    add column app_admin boolean not null default false;

update app_user
set app_admin = true
where id = (
    select min(id)
    from app_user
    where active = true
);

create table app_registration_invitation (
    id bigserial primary key,
    invite_token varchar(80) not null unique,
    created_by_user_id bigint not null references app_user(id),
    accepted_by_user_id bigint references app_user(id),
    revoked_at timestamptz,
    accepted_at timestamptz,
    created_at timestamptz not null default now(),
    check (length(trim(invite_token)) >= 36)
);

create index idx_app_registration_invitation_created_by_user_id
    on app_registration_invitation(created_by_user_id);

create index idx_app_registration_invitation_accepted_by_user_id
    on app_registration_invitation(accepted_by_user_id);
