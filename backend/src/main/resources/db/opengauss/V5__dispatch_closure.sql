-- Predictions contain only durations; no raw positions or device identifiers are retained.
ALTER TABLE mobility_pending_leg ADD COLUMN predicted_seconds INT;
ALTER TABLE travel_leg_summary ADD COLUMN predicted_seconds INT;
CREATE TABLE IF NOT EXISTS mobility_personal_travel_model (
 user_id BIGINT NOT NULL, from_building_id BIGINT NOT NULL, to_building_id BIGINT NOT NULL,
 minutes INT NOT NULL, sample_count INT NOT NULL, session_count INT NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(user_id,from_building_id,to_building_id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id),
 CHECK(minutes BETWEEN 1 AND 45), CHECK(sample_count>=5), CHECK(session_count>=3)
);
CREATE INDEX idx_personal_model_updated ON mobility_personal_travel_model(updated_at);
