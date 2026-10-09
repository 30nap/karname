-- The time step of the last accepted two-factor code: a code is accepted once, not for its whole
-- validity window.
ALTER TABLE users ADD COLUMN totp_last_step BIGINT;
