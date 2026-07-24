set local lock_timeout = '10s';

delete from calendar_member
where role_name = 'VIEWER';

alter table calendar_member
    drop constraint calendar_member_role_name_check;

alter table calendar_member
    add constraint calendar_member_role_name_check
        check (role_name in ('EDITOR', 'ADMIN'));
