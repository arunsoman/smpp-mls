-- SQL Queries to Verify Routing and Priority Handling
-- Run these in H2 Console: http://localhost:8080/h2-console

-- 1. Check all messages by priority and status
SELECT 
    priority,
    status,
    COUNT(*) as count
FROM sms_outbound
GROUP BY priority, status
ORDER BY priority DESC, status;

-- 2. Verify HP messages were sent before NP messages (despite being submitted later)
-- This shows the first 20 messages sent, ordered by when they were actually sent
SELECT 
    id,
    msisdn,
    message,
    priority,
    status,
    created_at,
    smsc_msg_id
FROM sms_outbound
WHERE status IN ('SENT', 'DELIVERED')
ORDER BY created_at ASC
LIMIT 20;

-- 3. Check message distribution across sessions
SELECT 
    session_id,
    priority,
    COUNT(*) as count
FROM sms_outbound
WHERE session_id IS NOT NULL
GROUP BY session_id, priority
ORDER BY session_id, priority DESC;

-- 4. Verify HP messages sent first (time-based analysis)
-- Compare average send time for HP vs NP messages
SELECT 
    priority,
    COUNT(*) as total_messages,
    MIN(created_at) as first_sent,
    MAX(created_at) as last_sent,
    AVG(TIMESTAMPDIFF(SECOND, created_at, CURRENT_TIMESTAMP)) as avg_age_seconds
FROM sms_outbound
WHERE status = 'SENT'
GROUP BY priority;

-- 5. Check retry counts (should be low if system is healthy)
SELECT 
    retry_count,
    COUNT(*) as count
FROM sms_outbound
WHERE retry_count > 0
GROUP BY retry_count
ORDER BY retry_count;

-- 6. View recent messages with full details
SELECT 
    id,
    msisdn,
    LEFT(message, 30) as message_preview,
    priority,
    status,
    session_id,
    smsc_msg_id,
    retry_count,
    created_at
FROM sms_outbound
ORDER BY created_at DESC
LIMIT 50;

-- 7. Check DLR (Delivery Receipt) status
SELECT 
    d.smsc_msg_id,
    o.msisdn,
    o.priority,
    d.dlr_status,
    d.received_at,
    TIMESTAMPDIFF(SECOND, o.created_at, d.received_at) as delivery_time_seconds
FROM sms_dlr d
JOIN sms_outbound o ON d.smsc_msg_id = o.smsc_msg_id
ORDER BY d.received_at DESC
LIMIT 20;

-- 8. Verify HP throttling (check if HP messages are limited to ~20% of total)
SELECT 
    CASE 
        WHEN priority = 'HIGH' THEN 'HIGH PRIORITY'
        ELSE 'NORMAL PRIORITY'
    END as priority_type,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM sms_outbound WHERE status = 'SENT'), 2) as percentage
FROM sms_outbound
WHERE status = 'SENT'
GROUP BY priority;

-- 9. Check messages per operator (routing verification)
SELECT 
    CASE 
        WHEN msisdn LIKE '9320%' OR msisdn LIKE '9325%' THEN 'AFTEL'
        WHEN msisdn LIKE '9379%' OR msisdn LIKE '9377%' OR msisdn LIKE '9372%' THEN 'Roshan'
        WHEN msisdn LIKE '9370%' OR msisdn LIKE '9371%' THEN 'AWCC'
        WHEN msisdn LIKE '9378%' OR msisdn LIKE '9376%' THEN 'MTN'
        WHEN msisdn LIKE '9374%' OR msisdn LIKE '9375%' THEN 'Salaam'
        ELSE 'Unknown'
    END as operator,
    COUNT(*) as message_count,
    SUM(CASE WHEN priority = 'HIGH' THEN 1 ELSE 0 END) as hp_count,
    SUM(CASE WHEN priority = 'NORMAL' THEN 1 ELSE 0 END) as np_count
FROM sms_outbound
GROUP BY 
    CASE 
        WHEN msisdn LIKE '9320%' OR msisdn LIKE '9325%' THEN 'AFTEL'
        WHEN msisdn LIKE '9379%' OR msisdn LIKE '9377%' OR msisdn LIKE '9372%' THEN 'Roshan'
        WHEN msisdn LIKE '9370%' OR msisdn LIKE '9371%' THEN 'AWCC'
        WHEN msisdn LIKE '9378%' OR msisdn LIKE '9376%' THEN 'MTN'
        WHEN msisdn LIKE '9374%' OR msisdn LIKE '9375%' THEN 'Salaam'
        ELSE 'Unknown'
    END
ORDER BY message_count DESC;

-- 10. Real-time monitoring query (run multiple times to see progress)
SELECT 
    status,
    priority,
    COUNT(*) as count
FROM sms_outbound
GROUP BY status, priority
ORDER BY 
    CASE status 
        WHEN 'QUEUED' THEN 1
        WHEN 'SENT' THEN 2
        WHEN 'DELIVERED' THEN 3
        WHEN 'RETRY' THEN 4
        WHEN 'FAILED' THEN 5
        ELSE 6
    END,
    priority DESC;
