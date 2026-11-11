-- Access-scope fingerprint protects command replay after permission changes.
ALTER TABLE idempotency_record ADD COLUMN permission_hash CHAR(64) NULL;
CREATE INDEX idx_session_presence ON auth_session(user_id,revoked_at,expires_at,last_seen_at);
CREATE INDEX idx_residence_room_current ON residence(room_id,checked_out_at);
CREATE INDEX idx_residence_student_history ON residence(student_id,checked_out_at,checked_in_at);
CREATE INDEX idx_repair_building_status_time ON repair_order(building_id,status,submitted_at);
CREATE INDEX idx_repair_student_time ON repair_order(reporter_student_id,submitted_at);
CREATE INDEX idx_repair_event_order_time ON repair_event(order_id,id);
CREATE INDEX idx_attachment_business ON file_asset(business_type,business_id,usage_type,state);
CREATE INDEX idx_hygiene_scope_time ON hygiene_inspection(building_id,voided_at,inspected_at);
CREATE INDEX idx_hygiene_room_latest ON hygiene_inspection(room_id,voided_at,inspected_at,id);
CREATE INDEX idx_visitor_scope_time ON visitor_registration(building_id,arrived_at);
CREATE INDEX idx_visitor_current ON visitor_registration(building_id,left_at);
CREATE INDEX idx_dispatch_task_manager_state ON dispatch_task(manager_user_id,state);
CREATE INDEX idx_dispatch_event_created ON dispatch_event(created_at);
