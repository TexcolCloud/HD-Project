-- Isolated performance fixture only. Never execute against the application database.
-- Deterministic fictional history, with valid existing foreign keys and no file contents.
SELECT 1 / CASE WHEN current_database() = 'dormlink_perf_test' THEN 1 ELSE 0 END AS isolated_database_guard;
INSERT INTO repair_order(id,order_no,reporter_student_id,reporter_name,residence_id,room_id,
 room_no,building_id,building_name,title,description,category,priority,contact_name,
 contact_phone,status,handler_user_id,handler_name,submitted_at,accepted_at,completed_at)
SELECT 1000000+g,'PERF-HISTORY-'||g,s.reporter_student_id,'Fictional student',s.residence_id,
 s.room_id,s.room_no,s.building_id,s.building_name,'Performance history '||g,
 'Fictional completed maintenance history','OTHER','NORMAL','Fictional student','10000000000',
 'COMPLETED',1,'Fictional manager',CURRENT_TIMESTAMP-(g%90+1)*INTERVAL '1 day',
 CURRENT_TIMESTAMP-(g%90+1)*INTERVAL '1 day'+INTERVAL '10 minute',
 CURRENT_TIMESTAMP-(g%90+1)*INTERVAL '1 day'+INTERVAL '40 minute'
FROM (SELECT * FROM repair_order WHERE id<1000000 ORDER BY id LIMIT 1) s,
 generate_series(1,100000) g
WHERE NOT EXISTS(SELECT 1 FROM repair_order WHERE id=1000000+g);
INSERT INTO repair_event(id,order_id,from_status,to_status,action,operator_id,operator_name,note,created_at)
SELECT 1000000+g,1000000+g,'PROCESSING','COMPLETED','FINISH',1,'Fictional manager',
 'Fictional performance event',CURRENT_TIMESTAMP
FROM generate_series(1,100000) g WHERE NOT EXISTS(SELECT 1 FROM repair_event WHERE id=1000000+g);
INSERT INTO file_asset(id,owner_user_id,storage_key,original_name,mime,size_bytes,usage_type,
 business_type,business_id,state,created_at)
SELECT 1000000+g,1,'perf-metadata-'||g||'.png','fixture.png','image/png',100,
 'RESULT','REPAIR',1000000+g,'BOUND',CURRENT_TIMESTAMP
FROM generate_series(1,100000) g WHERE NOT EXISTS(SELECT 1 FROM file_asset WHERE id=1000000+g);
ANALYZE repair_order;
ANALYZE repair_event;
ANALYZE file_asset;
SELECT 'repair_order',count(*) FROM repair_order
UNION ALL SELECT 'repair_event',count(*) FROM repair_event
UNION ALL SELECT 'file_asset',count(*) FROM file_asset;
