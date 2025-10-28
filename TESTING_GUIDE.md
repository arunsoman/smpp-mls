# SMPP-MLS Testing Guide

## Quick Start with Afghan SMPP Simulator

### 1. Start the Multi-Operator Simulator

```bash
# Start all Afghan operators (AFTEL, Roshan, AWCC, MTN, Salaam)
python afghan-smpp-simulator.py

# Or start specific operators only
python afghan-smpp-simulator.py --operators roshan mtn

# Disable automatic delivery receipts
python afghan-smpp-simulator.py --no-dlr
```

**What you'll see:**
```
======================================================================
Afghan SMPP Simulator - Multi-Operator
======================================================================

Starting operators:
  • AFTEL        - Port 2775 - Prefixes: 93-20, 93-25
  • Roshan       - Port 2776 - Prefixes: 93-79, 93-77, 93-72
  • AWCC         - Port 2777 - Prefixes: 93-70, 93-71
  • MTN          - Port 2778 - Prefixes: 93-78, 93-76
  • Salaam       - Port 2779 - Prefixes: 93-74, 93-75

Press Ctrl+C to stop
======================================================================
```

### 2. Start Your SMPP-MLS Application

```bash
# Using Gradle
./gradlew bootRun

# Or using the JAR
java -jar build/libs/smpp-mls-0.1.0.jar
```

**Expected Output:**
```
[aftel:aftel_client1] Attempting bind to localhost:2775
[aftel:aftel_client1] Bound successfully
[aftel:aftel_client2] Attempting bind to localhost:2775
[aftel:aftel_client2] Bound successfully
[roshan:roshan_user1] Attempting bind to localhost:2776
[roshan:roshan_user1] Bound successfully
[roshan:roshan_user2] Attempting bind to localhost:2776
[roshan:roshan_user2] Bound successfully
... (9 total sessions will bind)
```

### 3. Run the Feature Test Suite

```bash
# Basic tests
python test_smpp_features.py

# With load testing
python test_smpp_features.py --load
```

## Testing Scenarios

### Scenario 1: Test Multiple Binds per Operator

The simulator supports multiple concurrent binds per operator. Your app is configured with:
- **AFTEL**: 2 sessions (100 TPS total)
- **Roshan**: 2 sessions (200 TPS total)
- **AWCC**: 1 session (75 TPS)
- **MTN**: 2 sessions (180 TPS total)
- **Salaam**: 1 session (50 TPS)

**Total: 9 concurrent SMPP sessions, 605 TPS capacity**

### Scenario 2: Test Message Submission

```bash
# Submit to Roshan number
curl -X POST http://localhost:8080/api/sms/send \
  -H "Content-Type: application/json" \
  -d '{
    "msisdn": "93791234567",
    "message": "Hello Roshan!",
    "priority": "NORMAL"
  }'

# Submit to MTN number
curl -X POST http://localhost:8080/api/sms/send \
  -H "Content-Type: application/json" \
  -d '{
    "msisdn": "93781234567",
    "message": "Hello MTN!",
    "priority": "HIGH"
  }'
```

### Scenario 3: Test Delivery Receipts (DLR)

The simulator automatically sends DLRs 1-5 seconds after message submission.

**Watch the logs:**
```
[Roshan] Submit: dest=93791234567, msg='Hello Roshan!', id=Roshan00000001
[Roshan] DLR sent: id=Roshan00000001, stat=DELIVRD
```

### Scenario 4: Test High Priority vs Normal Priority

```bash
# Submit 10 HP messages
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/sms/send \
    -H "Content-Type: application/json" \
    -d "{\"msisdn\": \"93791234$i\", \"message\": \"HP Test $i\", \"priority\": \"HIGH\"}"
done

# Submit 10 NP messages
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/sms/send \
    -H "Content-Type: application/json" \
    -d "{\"msisdn\": \"93791235$i\", \"message\": \"NP Test $i\", \"priority\": \"NORMAL\"}"
done
```

**Expected:** HP messages should be sent first (up to 20% of TPS), then NP messages.

### Scenario 5: Test Session Failover

1. **Kill one operator** (Ctrl+C on simulator)
2. **Watch reconnection** in your app logs:
   ```
   [roshan:roshan_user1] Bind/connection error: Connection refused
   [roshan:roshan_user1] Reconnect sleeping for 5000 ms
   ```
3. **Restart simulator** - sessions should reconnect automatically

### Scenario 6: Test Enquire Link (Keepalive)

The simulator responds to enquire_link every 30 seconds (configured in your app).

**Watch simulator logs:**
```
[Roshan] [roshan_user1] Enquire link
```

This keeps the connection alive indefinitely.

## Monitoring

### Check Session Health

```bash
curl http://localhost:8080/api/smpp/health
```

**Response:**
```json
{
  "aftel:aftel_client1": true,
  "aftel:aftel_client2": true,
  "roshan:roshan_user1": true,
  "roshan:roshan_user2": true,
  "awcc:awcc_client1": true,
  "mtn:mtn_client1": true,
  "mtn:mtn_client2": true,
  "salaam:salaam_client1": true
}
```

### Check Prometheus Metrics

```bash
curl http://localhost:8080/actuator/prometheus | grep smpp
```

### Access H2 Database Console

1. Open: http://localhost:8080/h2-console
2. Connect:
   - JDBC URL: `jdbc:h2:mem:smppdb`
   - Username: `sa`
   - Password: `password`

3. Query messages:
```sql
SELECT * FROM sms_outbound ORDER BY created_at DESC LIMIT 10;
SELECT * FROM sms_dlr ORDER BY received_at DESC LIMIT 10;
```

## Afghan Operator Prefixes

| Operator | Prefixes | Port | Sessions |
|----------|----------|------|----------|
| AFTEL | 93-20, 93-25 | 2775 | 2 |
| Roshan | 93-79, 93-77, 93-72 | 2776 | 2 |
| AWCC (Etisalat) | 93-70, 93-71 | 2777 | 1 |
| MTN | 93-78, 93-76 | 2778 | 2 |
| Salaam | 93-74, 93-75 | 2779 | 1 |

## Troubleshooting

### Simulator not starting
```bash
# Check if ports are in use
netstat -an | findstr "2775 2776 2777 2778 2779"

# Kill processes using these ports
# Then restart simulator
```

### App not connecting
1. Ensure simulator is running first
2. Check firewall settings
3. Verify ports in application.yml match simulator

### No DLRs received
- Ensure simulator is running with DLR enabled (default)
- Check that your app implements DLR handling
- Look for `deliver_sm` in logs

## Load Testing

```bash
# Generate sustained load (50 msg/sec for 60 seconds)
for i in {1..3000}; do
  curl -X POST http://localhost:8080/api/sms/send \
    -H "Content-Type: application/json" \
    -d "{\"msisdn\": \"9379$(printf %07d $i)\", \"message\": \"Load test $i\", \"priority\": \"NORMAL\"}" &
  
  if [ $((i % 50)) -eq 0 ]; then
    sleep 1
  fi
done
```

## Next Steps

1. ✅ Test basic connectivity (all 9 sessions bind)
2. ✅ Test message submission to each operator
3. ✅ Test DLR reception
4. ✅ Test HP/NP priority handling
5. ✅ Test session reconnection
6. ✅ Test sustained load (100+ TPS)
7. 🔄 Implement prefix-based routing
8. 🔄 Add REST API endpoints
9. 🔄 Implement retry logic
10. 🔄 Add comprehensive metrics

---

**The simulator closely mimics real Afghan operator behavior with multiple binds, DLRs, and realistic timing!**
