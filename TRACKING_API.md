# Message Tracking API Documentation

## Overview

The Message Tracking API provides comprehensive tracking and reporting for SMS messages in the SMPP-MLS system. You can track messages using multiple identifiers and get a complete report of the message journey.

---

## Endpoints

### 1. Track by Message ID

**GET** `/api/track/message/{id}`

Track a message using the internal database message ID.

**Example:**
```bash
curl http://localhost:8080/api/track/message/123
```

---

### 2. Track by Request ID

**GET** `/api/track/request/{requestId}`

Track a message using the request ID returned when the message was submitted.

**Example:**
```bash
curl http://localhost:8080/api/track/request/872eb189-77ca-449f-ab04-bf69d6cac062
```

---

### 3. Track by Client Message ID

**GET** `/api/track/client/{clientMsgId}`

Track a message using your own client-provided message ID (for idempotency).

**Example:**
```bash
curl http://localhost:8080/api/track/client/my-unique-id-12345
```

---

### 4. Track by Phone Number (MSISDN)

**GET** `/api/track/phone/{msisdn}`

Track all messages sent to a specific phone number. Returns multiple messages if found.

**Example:**
```bash
curl http://localhost:8080/api/track/phone/93791234567
# or with + prefix
curl http://localhost:8080/api/track/phone/+93791234567
```

**Response:**
```json
{
  "msisdn": "+93791234567",
  "totalMessages": 5,
  "messages": [
    { /* message report 1 */ },
    { /* message report 2 */ },
    ...
  ]
}
```

---

### 5. Track by SMSC Message ID

**GET** `/api/track/smsc/{smscMsgId}`

Track a message using the SMSC-assigned message ID (returned after successful submission to operator).

**Example:**
```bash
curl http://localhost:8080/api/track/smsc/msg123
```

---

## Response Format

### Successful Response (200 OK)

```json
{
  "messageId": 123,
  "requestId": "872eb189-77ca-449f-ab04-bf69d6cac062",
  "clientMsgId": "my-unique-id-12345",
  "msisdn": "+93791234567",
  "message": "Hello World",
  "priority": "NORMAL",
  
  "routing": {
    "operator": "roshan",
    "sessionId": "roshan:roshan_user1"
  },
  
  "status": {
    "currentStatus": "SENT",
    "receivedAt": "2025-10-28T20:30:41.502704Z",
    "lastUpdatedAt": "2025-10-28T20:30:42.123456Z",
    "timeInSystemSeconds": 125
  },
  
  "submission": {
    "smscMessageId": "Roshan00000123",
    "submittedAt": "2025-10-28T20:30:41.502704Z",
    "submissionDelayMs": 621
  },
  
  "deliveryReceipts": [
    {
      "dlrId": 45,
      "status": "DELIVRD",
      "receivedAt": "2025-10-28T20:30:44.789012Z",
      "deliveryTimeMs": 3286,
      "deliveryTimeSeconds": 3.286
    }
  ],
  
  "deliveryStatus": "DELIVRD",
  "deliveredAt": "2025-10-28T20:30:44.789012Z",
  
  "timeline": {
    "1_received": "2025-10-28T20:30:41.502704Z",
    "2_submitted": "2025-10-28T20:30:42.123456Z",
    "3_delivered": "2025-10-28T20:30:44.789012Z"
  }
}
```

### Error Response (404 Not Found)

```json
{
  "error": "Message not found",
  "messageId": 999999
}
```

---

## Report Fields Explained

### Basic Information
- **messageId** - Internal database ID
- **requestId** - UUID assigned when message was submitted
- **clientMsgId** - Your client-provided ID (optional)
- **msisdn** - Destination phone number (E.164 format)
- **message** - Message text
- **priority** - Message priority (HIGH or NORMAL)

### Routing Information
- **operator** - Which operator the message was routed to (e.g., "roshan", "mtn")
- **sessionId** - Which SMPP session was used (e.g., "roshan:roshan_user1")

### Status & Timing
- **currentStatus** - Current message status:
  - `QUEUED` - Waiting to be sent
  - `SENT` - Successfully submitted to SMPP operator
  - `DELIVERED` - Delivery confirmed by operator
  - `RETRY` - Failed, will retry
  - `FAILED` - Permanently failed
- **receivedAt** - When system received the message
- **lastUpdatedAt** - Last status update time
- **timeInSystemSeconds** - How long the message has been in the system

### SMPP Submission Details
- **smscMessageId** - Message ID assigned by the operator's SMSC
- **submittedAt** - When message was submitted to SMPP
- **submissionDelayMs** - Time between receiving and submitting (milliseconds)

### Delivery Receipts (DLR)
- **dlrId** - DLR record ID
- **status** - Delivery status from operator (e.g., "DELIVRD", "EXPIRED", "UNDELIV")
- **receivedAt** - When DLR was received
- **deliveryTimeMs** - Total delivery time in milliseconds
- **deliveryTimeSeconds** - Total delivery time in seconds

### Retry Information (if applicable)
- **retryCount** - Number of retry attempts
- **lastAttemptAt** - When last retry was attempted
- **nextRetryAt** - When next retry will occur

### Timeline
A chronological summary of key events:
1. **1_received** - Message received by system
2. **2_submitted** - Message submitted to SMPP operator
3. **3_delivered** - Delivery confirmed

---

## Use Cases

### 1. Customer Support - Track Customer's Message

```bash
# Customer calls: "I sent a message to 93791234567, where is it?"
curl http://localhost:8080/api/track/phone/93791234567
```

### 2. API Integration - Check Your Message Status

```bash
# You submitted with clientMsgId "order-12345"
curl http://localhost:8080/api/track/client/order-12345
```

### 3. Debugging - Find Message by SMSC ID

```bash
# Operator reported issue with message "Roshan00000123"
curl http://localhost:8080/api/track/smsc/Roshan00000123
```

### 4. Monitoring - Check Recent Submission

```bash
# You got requestId from submission response
curl http://localhost:8080/api/track/request/872eb189-77ca-449f-ab04-bf69d6cac062
```

---

## Performance Metrics from Report

### Calculate Delivery Performance

```python
# From the JSON response:
submission_delay = report['submission']['submissionDelayMs']  # How fast we submitted
delivery_time = report['deliveryReceipts'][0]['deliveryTimeMs']  # Total delivery time

print(f"System processing: {submission_delay}ms")
print(f"Operator delivery: {delivery_time - submission_delay}ms")
print(f"Total end-to-end: {delivery_time}ms")
```

### Check SLA Compliance

```python
# Example: SLA is 5 seconds end-to-end
delivery_time_sec = report['deliveryReceipts'][0]['deliveryTimeSeconds']
sla_met = delivery_time_sec <= 5.0

print(f"Delivery time: {delivery_time_sec}s")
print(f"SLA (5s): {'✓ MET' if sla_met else '✗ MISSED'}")
```

---

## Testing

Run the test script:

```bash
python test_tracking_api.py
```

This will:
1. Submit a test message
2. Track it using all 5 methods
3. Display the complete report
4. Test error handling

---

## Integration Example

### Python

```python
import requests

def track_message(request_id):
    response = requests.get(f"http://localhost:8080/api/track/request/{request_id}")
    
    if response.status_code == 200:
        report = response.json()
        print(f"Status: {report['status']['currentStatus']}")
        print(f"Delivery: {report['deliveryStatus']}")
        
        if report['deliveryStatus'] == 'DELIVRD':
            delivery_time = report['deliveryReceipts'][0]['deliveryTimeSeconds']
            print(f"Delivered in {delivery_time}s")
        
        return report
    else:
        print(f"Message not found: {request_id}")
        return None

# Usage
track_message("872eb189-77ca-449f-ab04-bf69d6cac062")
```

### JavaScript

```javascript
async function trackMessage(requestId) {
  const response = await fetch(`http://localhost:8080/api/track/request/${requestId}`);
  
  if (response.ok) {
    const report = await response.json();
    console.log('Status:', report.status.currentStatus);
    console.log('Delivery:', report.deliveryStatus);
    
    if (report.deliveryStatus === 'DELIVRD') {
      const deliveryTime = report.deliveryReceipts[0].deliveryTimeSeconds;
      console.log(`Delivered in ${deliveryTime}s`);
    }
    
    return report;
  } else {
    console.error('Message not found:', requestId);
    return null;
  }
}

// Usage
trackMessage('872eb189-77ca-449f-ab04-bf69d6cac062');
```

### cURL

```bash
# Track and extract just the status
curl -s http://localhost:8080/api/track/request/872eb189-77ca-449f-ab04-bf69d6cac062 \
  | jq '.status.currentStatus'

# Track and extract delivery time
curl -s http://localhost:8080/api/track/request/872eb189-77ca-449f-ab04-bf69d6cac062 \
  | jq '.deliveryReceipts[0].deliveryTimeSeconds'
```

---

## Notes

- All timestamps are in ISO 8601 format (UTC)
- Phone numbers are normalized to E.164 format (+93...)
- The API returns 404 if message is not found
- Multiple DLRs may exist if operator sends duplicates
- Delivery status "PENDING" means no DLR received yet

---

## Related APIs

- **Submit Message:** `POST /api/sms/send`
- **Session Health:** `GET /api/smpp/health`
- **Database Verification:** `GET /api/verify/all`

---

*Last updated: October 29, 2025*
