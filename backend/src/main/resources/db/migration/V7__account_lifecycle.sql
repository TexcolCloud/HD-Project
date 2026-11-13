ALTER TABLE sys_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sys_user ADD COLUMN credential_expires_at TIMESTAMP(3);
ALTER TABLE sys_user ADD COLUMN account_version INT NOT NULL DEFAULT 0;
