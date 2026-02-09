-- ClickHouse Schema for SMPP Message Queue
-- This replaces H2 as the audit database

CREATE DATABASE IF NOT EXISTS smpp_archive;

-- Main message table (replaces H2 sms_outbound)
CREATE TABLE IF NOT EXISTS smpp_archive.sms_outbound (
    id UInt64,
    request_id String,
    client_msg_id Nullable(String),
    msisdn String,
    message String,
    source_addr Nullable(String),
    signature Nullable(String),
    status String,  -- QUEUED, SENT, FAILED, TIMEOUT, DELIVERED
    operator Nullable(String),
    session_id Nullable(String),
    smsc_msg_id Nullable(String),
    priority String DEFAULT 'NORMAL',
    error_message Nullable(String),
    created_at DateTime DEFAULT now(),
    updated_at DateTime DEFAULT now(),
    queued_duration_ms Nullable(UInt32),  -- Time spent in queue
    
    -- Indexes for fast queries
    INDEX idx_status status TYPE set(0) GRANULARITY 1,
    INDEX idx_client_msg_id client_msg_id TYPE bloom_filter GRANULARITY 1,
    INDEX idx_request_id request_id TYPE bloom_filter GRANULARITY 1,
    INDEX idx_smsc_msg_id smsc_msg_id TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
ORDER BY (created_at, id)
PARTITION BY toYYYYMM(created_at)
TTL created_at + INTERVAL 90 DAY  -- Auto-delete after 90 days
SETTINGS index_granularity = 8192;

-- DLR table (same as before)
CREATE TABLE IF NOT EXISTS smpp_archive.sms_dlr (
    id UInt64,
    sms_outbound_id UInt64,
    smsc_msg_id Nullable(String),
    stat Nullable(String),
    err Nullable(String),
    text Nullable(String),
    received_at DateTime DEFAULT now(),
    
    INDEX idx_outbound_id sms_outbound_id TYPE minmax GRANULARITY 1,
    INDEX idx_smsc_msg_id smsc_msg_id TYPE bloom_filter GRANULARITY 1
) ENGINE = MergeTree()
ORDER BY (received_at, id)
PARTITION BY toYYYYMM(received_at)
TTL received_at + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Materialized view for quick status counts
CREATE MATERIALIZED VIEW IF NOT EXISTS smpp_archive.mv_status_counts
ENGINE = SummingMergeTree()
ORDER BY (date, status)
AS SELECT
    toDate(created_at) as date,
    status,
    count() as count
FROM smpp_archive.sms_outbound
GROUP BY date, status;

-- Materialized view for operator statistics
CREATE MATERIALIZED VIEW IF NOT EXISTS smpp_archive.mv_operator_stats
ENGINE = SummingMergeTree()
ORDER BY (date, operator, status)
AS SELECT
    toDate(created_at) as date,
    ifNull(operator, 'UNKNOWN') as operator,
    status,
    count() as count,
    avg(queued_duration_ms) as avg_queue_duration
FROM smpp_archive.sms_outbound
GROUP BY date, operator, status;
