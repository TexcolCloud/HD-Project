-- openGauss PG compatibility: character-counted VARCHAR and preserved empty strings.
-- Additive migration for existing installations. Application checks ALTER columns before replay.
ALTER TABLE auth_session ADD COLUMN last_seen_at TIMESTAMP(3) NULL;
ALTER TABLE repair_order ADD COLUMN window_start TIMESTAMP(3) NULL;
ALTER TABLE repair_order ADD COLUMN window_end TIMESTAMP(3) NULL;
ALTER TABLE repair_order ADD COLUMN window_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE repair_order ADD COLUMN estimated_minutes INT NOT NULL DEFAULT 30;
ALTER TABLE repair_order ADD COLUMN visit_required BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE repair_order ADD COLUMN waiting_reason VARCHAR(500) NOT NULL DEFAULT '';
CREATE TABLE IF NOT EXISTS dispatch_manager (
 user_id BIGINT PRIMARY KEY, accepting_new_tasks BOOLEAN NOT NULL DEFAULT TRUE,
 position_building_id BIGINT, position_floor_no INT, position_updated_at TIMESTAMP(3),
 available_until TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(position_building_id) REFERENCES building(id)
);
CREATE TABLE IF NOT EXISTS dispatch_revision (
 id INT PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0, data_revision BIGINT NOT NULL DEFAULT 0,
 dirty BOOLEAN NOT NULL DEFAULT TRUE, reason VARCHAR(100) NOT NULL DEFAULT 'INITIAL',
 generated_at TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS dispatch_task (
 id BIGSERIAL PRIMARY KEY, order_id BIGINT NOT NULL, active_order_id BIGINT UNIQUE,
 manager_user_id BIGINT NOT NULL, state VARCHAR(24) NOT NULL DEFAULT 'ASSIGNED',
 next_user_id BIGINT UNIQUE, reason VARCHAR(500) NOT NULL DEFAULT '', version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(order_id) REFERENCES repair_order(id), FOREIGN KEY(manager_user_id) REFERENCES sys_user(id),
 CHECK(state IN ('ASSIGNED','EN_ROUTE','ON_SITE','BLOCKED','DONE','RELEASED')),
 CHECK((active_order_id IS NULL AND state IN ('DONE','RELEASED')) OR (active_order_id IS NOT NULL AND active_order_id=order_id AND state IN ('ASSIGNED','EN_ROUTE','ON_SITE','BLOCKED'))),
 CHECK((state='EN_ROUTE' AND next_user_id IS NOT NULL AND next_user_id=manager_user_id) OR (state<>'EN_ROUTE' AND next_user_id IS NULL))
);
CREATE TABLE IF NOT EXISTS dispatch_plan (
 revision BIGINT PRIMARY KEY, data_revision BIGINT NOT NULL, fingerprint CHAR(64) NOT NULL,
 result_json TEXT NOT NULL, reason VARCHAR(100) NOT NULL, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS dispatch_event (
 id BIGSERIAL PRIMARY KEY, order_id BIGINT, actor_user_id BIGINT,
 event_type VARCHAR(64) NOT NULL, detail_json TEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS dispatch_travel (
 from_building_id BIGINT NOT NULL, to_building_id BIGINT NOT NULL,
 minutes INT NOT NULL, estimated BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 0,
 PRIMARY KEY(from_building_id,to_building_id),
 FOREIGN KEY(from_building_id) REFERENCES building(id), FOREIGN KEY(to_building_id) REFERENCES building(id),
 CHECK(minutes BETWEEN 0 AND 240)
);
