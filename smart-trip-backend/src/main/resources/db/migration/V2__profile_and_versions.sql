ALTER TABLE sys_user ADD COLUMN bio VARCHAR(500) NOT NULL DEFAULT '', ADD COLUMN travel_preferences VARCHAR(1000) NOT NULL DEFAULT '', ADD COLUMN token_version INT NOT NULL DEFAULT 0;
ALTER TABLE trip_plan ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE trip_expense ADD COLUMN source_item_id BIGINT NULL, ADD COLUMN request_key VARCHAR(64) NULL, ADD UNIQUE KEY uk_expense_source (trip_id, source_item_id), ADD UNIQUE KEY uk_expense_request (trip_id, request_key);
