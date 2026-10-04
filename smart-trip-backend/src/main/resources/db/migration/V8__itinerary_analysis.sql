ALTER TABLE trip_item ADD COLUMN environment_type varchar(10) NOT NULL DEFAULT 'UNKNOWN', ADD COLUMN schedule_locked tinyint NOT NULL DEFAULT 0;
ALTER TABLE ai_task ADD COLUMN proposal_meta_json longtext NULL;
