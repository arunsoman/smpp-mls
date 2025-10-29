-- Performance indexes for sms_outbound table
-- These indexes dramatically improve query performance for high-volume operations

-- Index for status + session_id + priority queries (used by SessionSender every second)
CREATE INDEX IF NOT EXISTS idx_status_session_priority ON sms_outbound(status, session_id, priority);

-- Index for status + session_id queries (used by dashboard and monitoring)
CREATE INDEX IF NOT EXISTS idx_status_session ON sms_outbound(status, session_id);

-- Index for client_msg_id lookups (idempotency checks)
CREATE INDEX IF NOT EXISTS idx_client_msg_id ON sms_outbound(client_msg_id);

-- Index for smsc_msg_id lookups (DLR processing)
CREATE INDEX IF NOT EXISTS idx_smsc_msg_id ON sms_outbound(smsc_msg_id);

-- Index for retry processing
CREATE INDEX IF NOT EXISTS idx_status_next_retry ON sms_outbound(status, next_retry_at);

-- Index for time-based queries and reporting
CREATE INDEX IF NOT EXISTS idx_created_at ON sms_outbound(created_at DESC);

-- Index for request_id lookups
CREATE INDEX IF NOT EXISTS idx_request_id ON sms_outbound(request_id);

-- Composite index for operator-based queries
CREATE INDEX IF NOT EXISTS idx_operator_status ON sms_outbound(operator, status);
