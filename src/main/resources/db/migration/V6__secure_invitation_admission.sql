update app_invitation
set expires_at = created_at + interval '7 days'
where expires_at is null;

alter table app_invitation
    alter column expires_at set not null;

create table app_registration_bootstrap (
    singleton_id smallint primary key,
    consumed_at timestamptz,
    constraint app_registration_bootstrap_singleton_check
        check (singleton_id = 1)
);

insert into app_registration_bootstrap (singleton_id, consumed_at)
select
    1,
    case
        when exists (select 1 from app_user) then now()
        else null
    end;
