ALTER TABLE sms_outbound ADD COLUMN part_no INT NULL;
ALTER TABLE sms_outbound ADD COLUMN total_parts INT NULL;
ALTER TABLE sms_outbound ADD COLUMN concat_ref_num INT NULL;

CREATE INDEX IF NOT EXISTS idx_request_id ON sms_outbound (request_id);
