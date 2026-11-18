ALTER TABLE sys_user ADD COLUMN login_phone VARCHAR(11) UNIQUE;
ALTER TABLE sys_user ADD COLUMN login_phone_verified_at TIMESTAMP(3);
ALTER TABLE sys_user ADD COLUMN recovery_code_hash CHAR(64);

CREATE TABLE IF NOT EXISTS auth_phone_challenge (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT,
    session_id BIGINT,
    account_version INT,
    purpose VARCHAR(32) NOT NULL,
    phone VARCHAR(11),
    client_type VARCHAR(16) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMP(3),
    FOREIGN KEY (user_id) REFERENCES sys_user(id),
    FOREIGN KEY (session_id) REFERENCES auth_session(id)
);
CREATE INDEX idx_auth_challenge_user_purpose ON auth_phone_challenge(user_id,purpose,consumed_at);
CREATE INDEX idx_auth_challenge_expiry ON auth_phone_challenge(expires_at);

CREATE TABLE IF NOT EXISTS auth_password_reset (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_version INT NOT NULL,
    phone VARCHAR(11) NOT NULL,
    client_type VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMP(3),
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_auth_reset_user ON auth_password_reset(user_id);
CREATE INDEX idx_auth_reset_expiry ON auth_password_reset(expires_at);

CREATE TABLE IF NOT EXISTS auth_verification_limit (
    bucket_key VARCHAR(64) PRIMARY KEY,
    used_count INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP(3)
);
INSERT INTO auth_verification_limit(bucket_key)
    SELECT 'ISSUE' WHERE NOT EXISTS (SELECT 1 FROM auth_verification_limit WHERE bucket_key='ISSUE');
INSERT INTO auth_verification_limit(bucket_key)
    SELECT 'VERIFY' WHERE NOT EXISTS (SELECT 1 FROM auth_verification_limit WHERE bucket_key='VERIFY');
