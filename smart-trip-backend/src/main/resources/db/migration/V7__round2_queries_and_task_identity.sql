ALTER TABLE post_interaction ADD INDEX idx_post_action(post_id,action_type);
ALTER TABLE square_post ADD INDEX idx_feed_order(is_deleted,update_time,id), ADD INDEX idx_author_feed(user_id,is_deleted,update_time,id);
ALTER TABLE ai_task ADD COLUMN request_hash CHAR(64) NULL, ADD COLUMN quota_reserved_date DATE NULL;
UPDATE ai_task SET quota_reserved_date=DATE(update_time) WHERE quota_reserved=1;
