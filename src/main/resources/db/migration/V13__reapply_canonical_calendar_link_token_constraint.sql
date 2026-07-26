-- Migration 10 shortened calendar link tokens and added calendar_public_token_check, but its
-- constraint was later tightened from '^[A-Za-z0-9_-]{11}$' to the canonical form below. Migration
-- 10 is a Java migration without a checksum, so Flyway could not detect that edit and never
-- re-applied it: every database migrated before the edit still carries the loose constraint, which
-- accepts 11-character tokens whose final character cannot occur when encoding exactly eight bytes.
-- This migration re-applies the canonical constraint as a forward step so already-migrated
-- databases converge with newly created ones.
--
-- No data repair is needed. Migration 10 regenerated every token with the canonical generator, and
-- every token created since comes from the same generator, so no stored token can violate this.

alter table calendar
    drop constraint if exists calendar_public_token_check;

alter table calendar
    add constraint calendar_public_token_check
        check (public_token ~ '^[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]$');
