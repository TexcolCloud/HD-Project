CREATE TABLE IF NOT EXISTS service_message (
 id BIGSERIAL PRIMARY KEY, source_key VARCHAR(160) NOT NULL,
 recipient_user_id BIGINT NOT NULL, building_id BIGINT, kind VARCHAR(40) NOT NULL,
 title VARCHAR(100) NOT NULL, body VARCHAR(300) NOT NULL, business_type VARCHAR(24) NOT NULL,
 business_id BIGINT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempt_count INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP(3) NOT NULL, delivered_at TIMESTAMP(3), last_error VARCHAR(40),
 read_at TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
 UNIQUE(source_key,recipient_user_id), FOREIGN KEY(recipient_user_id) REFERENCES sys_user(id),
 FOREIGN KEY(building_id) REFERENCES building(id),
 CHECK(delivery_state IN ('PENDING','DELIVERED','FAILED'))
);
CREATE INDEX idx_message_delivery ON service_message(delivery_state,next_attempt_at,id);
CREATE INDEX idx_message_inbox ON service_message(recipient_user_id,delivery_state,read_at,id);
CREATE TABLE IF NOT EXISTS service_followup_lock (
 id INT PRIMARY KEY, pending_cursor BIGINT NOT NULL DEFAULT 0, blocked_cursor BIGINT NOT NULL DEFAULT 0,
 visitor_cursor BIGINT NOT NULL DEFAULT 0, remediation_cursor BIGINT NOT NULL DEFAULT 0
);
INSERT INTO service_followup_lock(id) SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM service_followup_lock WHERE id=1);
CREATE TABLE IF NOT EXISTS hygiene_remediation (
 id BIGSERIAL PRIMARY KEY, inspection_id BIGINT NOT NULL UNIQUE,
 room_id BIGINT NOT NULL, room_no VARCHAR(20) NOT NULL, building_id BIGINT NOT NULL,
 building_name VARCHAR(50) NOT NULL, responsible_student_id BIGINT NOT NULL,
 responsible_name VARCHAR(50) NOT NULL, issue_note VARCHAR(500) NOT NULL,
 due_at TIMESTAMP(3) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
 feedback_note VARCHAR(1000) NOT NULL DEFAULT '', review_note VARCHAR(1000) NOT NULL DEFAULT '',
 related_recheck_id BIGINT, created_at TIMESTAMP(3) NOT NULL, submitted_at TIMESTAMP(3),
 closed_at TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(inspection_id) REFERENCES hygiene_inspection(id), FOREIGN KEY(room_id) REFERENCES dorm_room(id),
 FOREIGN KEY(building_id) REFERENCES building(id), FOREIGN KEY(responsible_student_id) REFERENCES student(id),
 FOREIGN KEY(related_recheck_id) REFERENCES hygiene_inspection(id),
 CHECK(status IN ('OPEN','SUBMITTED','CLOSED','CANCELLED'))
);
CREATE INDEX idx_remediation_student ON hygiene_remediation(responsible_student_id,status,id);
CREATE INDEX idx_remediation_due ON hygiene_remediation(status,due_at,id);
CREATE TABLE IF NOT EXISTS ai_advice_order (
 suggestion_id BIGINT NOT NULL, order_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, PRIMARY KEY(suggestion_id,order_id),
 FOREIGN KEY(suggestion_id) REFERENCES ai_invocation(id), FOREIGN KEY(order_id) REFERENCES repair_order(id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE IF NOT EXISTS ai_advice_feedback (
 id BIGSERIAL PRIMARY KEY, order_id BIGINT NOT NULL, suggestion_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL, operator_name VARCHAR(50) NOT NULL, decision VARCHAR(16) NOT NULL,
 reason VARCHAR(1000) NOT NULL, outcome VARCHAR(1000) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 FOREIGN KEY(suggestion_id,order_id) REFERENCES ai_advice_order(suggestion_id,order_id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id), CHECK(decision IN ('ADOPTED','MODIFIED','IGNORED'))
);
CREATE INDEX idx_ai_feedback_order ON ai_advice_feedback(order_id,id);

