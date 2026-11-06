CREATE TABLE IF NOT EXISTS sys_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL, display_name VARCHAR(50) NOT NULL,
 role VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 CHECK (role IN ('STUDENT','MANAGER'))
);
CREATE TABLE IF NOT EXISTS auth_session (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE,
 csrf_token VARCHAR(100) NOT NULL, client_type VARCHAR(16) NOT NULL, expires_at TIMESTAMP(3) NOT NULL,
 revoked_at TIMESTAMP(3), created_at TIMESTAMP(3) NOT NULL, FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE IF NOT EXISTS building (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(20) NOT NULL UNIQUE, name VARCHAR(50) NOT NULL,
 floor_count INT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 0,
 CHECK(floor_count>0)
);
CREATE TABLE IF NOT EXISTS manager_building (
 manager_user_id BIGINT NOT NULL, building_id BIGINT NOT NULL,
 PRIMARY KEY(manager_user_id,building_id), FOREIGN KEY(manager_user_id) REFERENCES sys_user(id),
 FOREIGN KEY(building_id) REFERENCES building(id)
);
CREATE TABLE IF NOT EXISTS student (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL UNIQUE, student_no VARCHAR(32) NOT NULL UNIQUE,
 name VARCHAR(50) NOT NULL, college VARCHAR(100) NOT NULL DEFAULT '', major VARCHAR(100) NOT NULL DEFAULT '',
 grade VARCHAR(20) NOT NULL DEFAULT '', class_name VARCHAR(100) NOT NULL DEFAULT '', phone VARCHAR(20) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_by BIGINT NOT NULL, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(created_by) REFERENCES sys_user(id)
);
CREATE TABLE IF NOT EXISTS dorm_room (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, building_id BIGINT NOT NULL, room_no VARCHAR(20) NOT NULL,
 floor_no INT NOT NULL, capacity INT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 remark VARCHAR(500) NOT NULL DEFAULT '', version INT NOT NULL DEFAULT 0,
 UNIQUE(building_id,room_no), FOREIGN KEY(building_id) REFERENCES building(id), CHECK(capacity>0)
);
CREATE TABLE IF NOT EXISTS residence (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, room_id BIGINT NOT NULL,
 building_id BIGINT NOT NULL, room_no VARCHAR(20) NOT NULL, building_name VARCHAR(50) NOT NULL,
 checked_in_at TIMESTAMP(3) NOT NULL, checked_out_at TIMESTAMP(3), active_student_id BIGINT UNIQUE,
 reason VARCHAR(200) NOT NULL DEFAULT '', operator_id BIGINT NOT NULL, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(student_id) REFERENCES student(id), FOREIGN KEY(room_id) REFERENCES dorm_room(id),
 FOREIGN KEY(building_id) REFERENCES building(id), FOREIGN KEY(operator_id) REFERENCES sys_user(id),
 CHECK(checked_out_at IS NULL OR checked_out_at>=checked_in_at),
 CHECK((checked_out_at IS NULL AND active_student_id IS NOT NULL AND active_student_id=student_id) OR (checked_out_at IS NOT NULL AND active_student_id IS NULL))
);
CREATE TABLE IF NOT EXISTS visitor_registration (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, visitor_name VARCHAR(50) NOT NULL, phone VARCHAR(20) NOT NULL,
 visited_student_id BIGINT NOT NULL, student_name VARCHAR(50) NOT NULL, room_id BIGINT NOT NULL,
 room_no VARCHAR(20) NOT NULL, building_id BIGINT NOT NULL, building_name VARCHAR(50) NOT NULL,
 purpose VARCHAR(200) NOT NULL, remark VARCHAR(500) NOT NULL DEFAULT '', arrived_at TIMESTAMP(3) NOT NULL,
 left_at TIMESTAMP(3), operator_id BIGINT NOT NULL, left_operator_id BIGINT, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(visited_student_id) REFERENCES student(id), FOREIGN KEY(room_id) REFERENCES dorm_room(id),
 FOREIGN KEY(building_id) REFERENCES building(id), CHECK(left_at IS NULL OR left_at>=arrived_at)
);
CREATE TABLE IF NOT EXISTS repair_order (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(40) NOT NULL UNIQUE,
 reporter_student_id BIGINT NOT NULL, reporter_name VARCHAR(50) NOT NULL,
 residence_id BIGINT NOT NULL, room_id BIGINT NOT NULL, room_no VARCHAR(20) NOT NULL,
 building_id BIGINT NOT NULL, building_name VARCHAR(50) NOT NULL, title VARCHAR(80) NOT NULL,
 description VARCHAR(1000) NOT NULL, category VARCHAR(24) NOT NULL, priority VARCHAR(16) NOT NULL,
 urgent_reason VARCHAR(200) NOT NULL DEFAULT '', contact_name VARCHAR(50) NOT NULL,
 contact_phone VARCHAR(20) NOT NULL, contact_time_slot VARCHAR(100) NOT NULL DEFAULT '',
 status VARCHAR(32) NOT NULL, handler_user_id BIGINT, handler_name VARCHAR(50), related_order_id BIGINT,
 submitted_at TIMESTAMP(3) NOT NULL, accepted_at TIMESTAMP(3), completed_at TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(reporter_student_id) REFERENCES student(id), FOREIGN KEY(residence_id) REFERENCES residence(id),
 FOREIGN KEY(room_id) REFERENCES dorm_room(id), FOREIGN KEY(building_id) REFERENCES building(id),
 FOREIGN KEY(handler_user_id) REFERENCES sys_user(id), FOREIGN KEY(related_order_id) REFERENCES repair_order(id),
 CHECK(status IN ('SUBMITTED','ACCEPTED','PROCESSING','PENDING_CONFIRMATION','COMPLETED','CANCELLED','REJECTED')),
 CHECK(category IN ('UTILITIES','FURNITURE','NETWORK','OTHER')), CHECK(priority IN ('NORMAL','URGENT'))
);
CREATE TABLE IF NOT EXISTS repair_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, from_status VARCHAR(32), to_status VARCHAR(32) NOT NULL,
 action VARCHAR(32) NOT NULL, operator_id BIGINT NOT NULL, operator_name VARCHAR(50) NOT NULL,
 note VARCHAR(1000) NOT NULL DEFAULT '', created_at TIMESTAMP(3) NOT NULL, FOREIGN KEY(order_id) REFERENCES repair_order(id)
);
CREATE TABLE IF NOT EXISTS hygiene_inspection (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, room_id BIGINT NOT NULL, room_no VARCHAR(20) NOT NULL,
 building_id BIGINT NOT NULL, building_name VARCHAR(50) NOT NULL, inspection_date DATE NOT NULL,
 round VARCHAR(20) NOT NULL, inspected_at TIMESTAMP(3) NOT NULL,
 floor_score INT NOT NULL, desk_score INT NOT NULL, bed_score INT NOT NULL, bathroom_score INT NOT NULL,
 total_score INT NOT NULL, grade VARCHAR(16) NOT NULL, issue_note VARCHAR(500) NOT NULL DEFAULT '',
 remediation_note VARCHAR(500) NOT NULL DEFAULT '', inspector_user_id BIGINT NOT NULL,
 inspector_name VARCHAR(50) NOT NULL, related_inspection_id BIGINT, voided_at TIMESTAMP(3), active_guard INT DEFAULT 1,
 version INT NOT NULL DEFAULT 0, UNIQUE(room_id,inspection_date,round,active_guard),
 FOREIGN KEY(room_id) REFERENCES dorm_room(id), FOREIGN KEY(building_id) REFERENCES building(id),
 FOREIGN KEY(related_inspection_id) REFERENCES hygiene_inspection(id),
 CHECK(floor_score BETWEEN 0 AND 25), CHECK(desk_score BETWEEN 0 AND 25),
 CHECK(bed_score BETWEEN 0 AND 25), CHECK(bathroom_score BETWEEN 0 AND 25),
 CHECK(total_score=floor_score+desk_score+bed_score+bathroom_score)
);
CREATE TABLE IF NOT EXISTS file_asset (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_user_id BIGINT NOT NULL, storage_key VARCHAR(100) NOT NULL UNIQUE,
 original_name VARCHAR(150) NOT NULL, mime VARCHAR(32) NOT NULL, size_bytes BIGINT NOT NULL,
 usage_type VARCHAR(20) NOT NULL, business_type VARCHAR(24), business_id BIGINT, repair_event_id BIGINT,
 state VARCHAR(16) NOT NULL DEFAULT 'TEMP', created_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(owner_user_id) REFERENCES sys_user(id), FOREIGN KEY(repair_event_id) REFERENCES repair_event(id)
);
CREATE TABLE IF NOT EXISTS audit_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, operator_id BIGINT NOT NULL, operator_name VARCHAR(50) NOT NULL,
 action VARCHAR(64) NOT NULL, business_type VARCHAR(24) NOT NULL, business_id BIGINT,
 note VARCHAR(1000) NOT NULL DEFAULT '', changes TEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS idempotency_record (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, route_key VARCHAR(150) NOT NULL,
 request_key VARCHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL, response_data TEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, UNIQUE(user_id,route_key,request_key)
);
CREATE TABLE IF NOT EXISTS ai_invocation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, model VARCHAR(80) NOT NULL,
 status VARCHAR(24) NOT NULL, latency_ms INT NOT NULL, input_tokens INT, output_tokens INT,
 created_at TIMESTAMP(3) NOT NULL
);
