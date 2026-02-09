# Fixing client_msg_id Duplicates

## Problem

You're experiencing `NonUniqueResultException` because there are **duplicate `client_msg_id` values** in the database (3 in your case).

## Root Cause

**Race Condition** in `SubmissionService.submit()`:

```java
// BEFORE (vulnerable to race condition):
if (clientMsgId != null) {
    List<Entity> existing = findByClientMsgId(clientMsgId);  // ← Multiple threads check
    if (existing.isEmpty()) {
        // All threads see empty, all proceed to insert!
        save(new Entity(clientMsgId));  // ← Creates duplicates
    }
}
```

**What happens**:
1. Request A checks: `findByClientMsgId("ABC")` → empty
2. Request B checks: `findByClientMsgId("ABC")` → empty (A hasn't inserted yet)
3. Request C checks: `findByClientMsgId("ABC")` → empty (A, B haven't inserted yet)
4. All 3 insert → **3 duplicates!**

## Solution

### 1. Immediate Fix: Synchronized Idempotency Check ✅

Updated `SubmissionService.java` to use `synchronized` block:

```java
String lockKey = req.getClientMsgId().intern();
synchronized (lockKey) {
    // Now only ONE thread at a time can check+insert for this clientMsgId
    List<Entity> existing = findByClientMsgId(clientMsgId);
    if (existing.isEmpty()) {
        save(new Entity(clientMsgId));
    }
}
```

This prevents **new** duplicates from being created.

### 2. Cleanup Existing Duplicates

Run [`cleanup_duplicates.sql`](file:///home/arun/IdeaProjects/smpp-mls/cleanup_duplicates.sql):

```bash
# Access H2 console
http://localhost:2222/h2-console

# Run the SQL script to:
# 1. Find duplicates
# 2. Preview what will be deleted
# 3. Delete duplicates (keeps oldest entry)
# 4. Verify cleanup
```

**Strategy**: Keep the **first** (oldest) entry for each `client_msg_id`, delete the rest.

### 3. Apply Database Constraint

After cleanup, apply the unique constraint from [`apply_indexes.sql`](file:///home/arun/IdeaProjects/smpp-mls/apply_indexes.sql):

```sql
ALTER TABLE sms_outbound ADD CONSTRAINT uk_client_msg_id UNIQUE (client_msg_id);
```

This enforces uniqueness at the database level.

## Steps to Fix

1. ✅ **Deploy code fix** (synchronized block) - prevents new duplicates
2. ⏳ **Run cleanup script** - removes existing duplicates
3. ⏳ **Apply unique constraint** - enforces uniqueness going forward

## Why This Happened

- `unique = true` in JPA entity **only affects schema generation**
- Since you use `ddl-auto: update`, Hibernate doesn't automatically add constraints to existing tables
- The constraint must be applied manually via SQL

## Verification

After cleanup, this query should return 0 rows:

```sql
SELECT client_msg_id, COUNT(*) 
FROM sms_outbound 
WHERE client_msg_id IS NOT NULL 
GROUP BY client_msg_id 
HAVING COUNT(*) > 1;
```
