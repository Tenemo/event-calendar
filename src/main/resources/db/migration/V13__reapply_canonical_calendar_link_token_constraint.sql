-- The committed form of migration 10 has always installed the canonical constraint below. A local
-- development database could nevertheless have run an earlier, uncommitted form of that Java
-- migration. Because migration 10 does not provide a checksum, Flyway would not detect a later edit
-- to it. Migrations 13 through 15 therefore re-apply the canonical constraint as defensive forward
-- steps. They are a no-op for the constraint semantics of databases built only from committed
-- revisions. This first step skips the table scan and commits the short ACCESS EXCLUSIVE lock before
-- migration 14 validates existing rows under the weaker SHARE UPDATE EXCLUSIVE lock. Migration 15
-- then performs only the short constraint-name swap.
--
-- No data repair is needed. Migration 10 regenerated every token with the canonical generator, and
-- every token created since comes from the same generator, so no stored token can violate this.

set local lock_timeout = '10s';

alter table calendar
    add constraint calendar_public_token_check_v13
        check (public_token ~ '^[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]$') not valid;
