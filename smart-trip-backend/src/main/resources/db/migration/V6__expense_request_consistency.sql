ALTER TABLE trip_expense DROP INDEX uk_expense_source, ADD INDEX idx_expense_source(trip_id,source_item_id), MODIFY COLUMN request_key varchar(80) NULL;
