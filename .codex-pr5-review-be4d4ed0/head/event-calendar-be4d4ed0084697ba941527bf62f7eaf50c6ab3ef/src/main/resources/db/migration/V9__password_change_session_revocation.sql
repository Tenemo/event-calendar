alter table app_user
    add column password_version bigint not null default 0,
    add constraint app_user_password_version_check
        check (password_version >= 0);
