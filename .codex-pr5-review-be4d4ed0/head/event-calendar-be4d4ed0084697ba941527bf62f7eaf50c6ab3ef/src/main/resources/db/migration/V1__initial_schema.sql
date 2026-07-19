create table app_user (
    id bigserial primary key,
    username varchar(80) not null unique,
    display_name varchar(160) not null,
    password_hash text not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (length(trim(username)) > 0),
    check (length(trim(display_name)) > 0)
);

create table calendar (
    id bigserial primary key,
    name varchar(160) not null,
    description text,
    public_token varchar(80) not null unique,
    timezone varchar(80) not null default 'Europe/Warsaw',
    public_access_enabled boolean not null default true,
    active boolean not null default true,
    created_by_user_id bigint not null references app_user(id),
    version integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (length(trim(name)) > 0),
    check (length(trim(public_token)) >= 36)
);

create index idx_calendar_created_by_user_id
    on calendar(created_by_user_id);

create table calendar_member (
    calendar_id bigint not null references calendar(id) on delete cascade,
    user_id bigint not null references app_user(id) on delete cascade,
    role_name varchar(20) not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (calendar_id, user_id),
    check (role_name in ('VIEWER', 'EDITOR', 'ADMIN'))
);

create index idx_calendar_member_user_id
    on calendar_member(user_id);

create index idx_calendar_member_role_name
    on calendar_member(role_name);

create table calendar_invitation (
    id bigserial primary key,
    calendar_id bigint not null references calendar(id) on delete cascade,
    invite_token varchar(80) not null unique,
    role_name varchar(20) not null,
    created_by_user_id bigint not null references app_user(id),
    accepted_by_user_id bigint references app_user(id),
    revoked_at timestamptz,
    accepted_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    check (role_name in ('VIEWER', 'EDITOR')),
    check (length(trim(invite_token)) >= 36)
);

create index idx_calendar_invitation_calendar_id
    on calendar_invitation(calendar_id);

create table calendar_event (
    id bigserial primary key,
    calendar_id bigint not null references calendar(id) on delete cascade,
    title varchar(200) not null,
    description text,
    location varchar(200),
    start_at timestamptz not null,
    end_at timestamptz not null,
    all_day boolean not null default false,
    created_by_user_id bigint references app_user(id),
    updated_by_user_id bigint references app_user(id),
    version integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (length(trim(title)) > 0),
    check (end_at > start_at)
);

create index idx_calendar_event_calendar_start
    on calendar_event(calendar_id, start_at);

create index idx_calendar_event_calendar_end
    on calendar_event(calendar_id, end_at);

create table audit_log (
    id bigserial primary key,
    actor_user_id bigint references app_user(id),
    calendar_id bigint references calendar(id),
    entity_type varchar(80) not null,
    entity_id bigint,
    action varchar(80) not null,
    details text,
    created_at timestamptz not null default now()
);

create index idx_audit_log_created_at
    on audit_log(created_at);

create index idx_audit_log_calendar_id
    on audit_log(calendar_id);

create index idx_audit_log_entity
    on audit_log(entity_type, entity_id);
