-- Cleanup script for duplicate client_msg_id entries
-- Run this BEFORE applying the unique constraint

-- Step 1: Find all duplicate client_msg_id values
SELECT client_msg_id, COUNT(*) as count
FROM sms_outbound
WHERE client_msg_id IS NOT NULL
GROUP BY client_msg_id
HAVING COUNT(*) > 1
ORDER BY count DESC;

-- Step 2: For each duplicate, keep the OLDEST entry (lowest ID) and delete the rest
-- This preserves the first submission for idempotency

-- Preview what will be deleted (run this first to verify)
SELECT s.*
FROM sms_outbound s
WHERE s.client_msg_id IN (
    SELECT client_msg_id
    FROM sms_outbound
    WHERE client_msg_id IS NOT NULL
    GROUP BY client_msg_id
    HAVING COUNT(*) > 1
)
AND s.id NOT IN (
    SELECT MIN(id)
    FROM sms_outbound
    WHERE client_msg_id IS NOT NULL
    GROUP BY client_msg_id
    HAVING COUNT(*) > 1
)
ORDER BY s.client_msg_id, s.id;

-- Step 3: Delete duplicates (CAUTION: This will permanently delete data!)
-- Uncomment and run after verifying the preview above
/*
DELETE FROM sms_outbound
WHERE id IN (
    SELECT s.id
    FROM sms_outbound s
    WHERE s.client_msg_id IN (
        SELECT client_msg_id
        FROM sms_outbound
        WHERE client_msg_id IS NOT NULL
        GROUP BY client_msg_id
        HAVING COUNT(*) > 1
    )
    AND s.id NOT IN (
        SELECT MIN(id)
        FROM sms_outbound
        WHERE client_msg_id IS NOT NULL
        GROUP BY client_msg_id
        HAVING COUNT(*) > 1
    )
);
*/

-- Step 4: Verify no duplicates remain
SELECT client_msg_id, COUNT(*) as count
FROM sms_outbound
WHERE client_msg_id IS NOT NULL
GROUP BY client_msg_id
HAVING COUNT(*) > 1;

-- Should return 0 rows

-- Step 5: Now you can safely apply the unique constraint
-- ALTER TABLE sms_outbound ADD CONSTRAINT uk_client_msg_id UNIQUE (client_msg_id);
