-- Device work sessions are independent of dispatch settings versions.
CREATE TABLE IF NOT EXISTS field_work_session (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, active_user_id BIGINT UNIQUE,
 auth_session_id BIGINT NOT NULL, client_instance_id VARCHAR(80) NOT NULL,
 source_epoch INT NOT NULL, state VARCHAR(16) NOT NULL, consent_version VARCHAR(40) NOT NULL,
 background_requested BOOLEAN NOT NULL DEFAULT FALSE, lease_expires_at TIMESTAMP(3) NOT NULL,
 started_at TIMESTAMP(3) NOT NULL, ended_at TIMESTAMP(3), last_sample_at TIMESTAMP(3),
 last_sequence BIGINT NOT NULL DEFAULT 0, stream_id VARCHAR(80), last_elapsed_ms DOUBLE PRECISION,
 version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(auth_session_id) REFERENCES auth_session(id),
 CHECK(state IN ('ACTIVE','PAUSED','ENDED','EXPIRED')),
 CHECK((state IN ('ACTIVE','PAUSED') AND active_user_id=user_id AND active_user_id IS NOT NULL)
 OR (state IN ('ENDED','EXPIRED') AND active_user_id IS NULL))
);
CREATE INDEX idx_work_session_auth_state ON field_work_session(auth_session_id,state,lease_expires_at);
CREATE TABLE IF NOT EXISTS campus_anchor (
 id VARCHAR(36) PRIMARY KEY, building_id BIGINT NOT NULL, floor_no INT,
 name VARCHAR(80) NOT NULL, scan_code VARCHAR(80) NOT NULL UNIQUE,
 latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, radius_meters DOUBLE PRECISION,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(building_id) REFERENCES building(id), CHECK(floor_no IS NULL OR floor_no>0)
);
CREATE INDEX idx_anchor_building ON campus_anchor(building_id,enabled);
CREATE TABLE IF NOT EXISTS manager_context_snapshot (
 user_id BIGINT PRIMARY KEY, session_id VARCHAR(36) NOT NULL, source VARCHAR(16) NOT NULL DEFAULT 'NONE',
 building_id BIGINT, floor_no INT, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION,
 accuracy_meters DOUBLE PRECISION, speed_mps DOUBLE PRECISION, motion VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 quality VARCHAR(16) NOT NULL DEFAULT 'LOW', observed_at TIMESTAMP(3), expires_at TIMESTAMP(3),
 anchor_id VARCHAR(36), anchor_confirmed_at TIMESTAMP(3), context_version INT NOT NULL DEFAULT 0,
 explanation VARCHAR(300) NOT NULL DEFAULT '',
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(session_id) REFERENCES field_work_session(id),
 FOREIGN KEY(building_id) REFERENCES building(id), FOREIGN KEY(anchor_id) REFERENCES campus_anchor(id)
);
CREATE TABLE IF NOT EXISTS mobility_batch_receipt (
 session_id VARCHAR(36) NOT NULL, batch_id VARCHAR(80) NOT NULL, request_hash CHAR(64) NOT NULL,
 ack_json TEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(session_id,batch_id), FOREIGN KEY(session_id) REFERENCES field_work_session(id)
);
CREATE INDEX idx_mobility_receipt_created ON mobility_batch_receipt(created_at);
CREATE TABLE IF NOT EXISTS mobility_pending_leg (
 user_id BIGINT PRIMARY KEY, session_id VARCHAR(36) NOT NULL, source_epoch INT NOT NULL,
 order_id BIGINT NOT NULL, from_anchor_id VARCHAR(36) NOT NULL, departed_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(session_id) REFERENCES field_work_session(id),
 FOREIGN KEY(from_anchor_id) REFERENCES campus_anchor(id), FOREIGN KEY(order_id) REFERENCES repair_order(id)
);
CREATE TABLE IF NOT EXISTS travel_leg_summary (
 id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL,
 from_anchor_id VARCHAR(36) NOT NULL, to_anchor_id VARCHAR(36) NOT NULL,
 from_building_id BIGINT NOT NULL, to_building_id BIGINT NOT NULL,
 duration_seconds INT NOT NULL, quality VARCHAR(16) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(session_id) REFERENCES field_work_session(id),
 FOREIGN KEY(from_anchor_id) REFERENCES campus_anchor(id), FOREIGN KEY(to_anchor_id) REFERENCES campus_anchor(id),
 CHECK(duration_seconds BETWEEN 10 AND 1800)
);
CREATE INDEX idx_leg_learning ON travel_leg_summary(from_building_id,to_building_id,created_at);
CREATE TABLE IF NOT EXISTS mobility_travel_model (
 from_building_id BIGINT NOT NULL, to_building_id BIGINT NOT NULL,
 minutes INT NOT NULL, sample_count INT NOT NULL, session_count INT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 0, updated_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(from_building_id,to_building_id), CHECK(minutes BETWEEN 1 AND 30)
);
CREATE TABLE IF NOT EXISTS mobility_model_revision (
 from_building_id BIGINT NOT NULL, to_building_id BIGINT NOT NULL, version INT NOT NULL,
 minutes INT NOT NULL, enabled BOOLEAN NOT NULL, sample_count INT NOT NULL, session_count INT NOT NULL,
 reason VARCHAR(80) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(from_building_id,to_building_id,version)
);
