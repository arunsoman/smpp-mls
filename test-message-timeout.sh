#!/bin/bash

# Configuration
API_BASE="http://localhost:2222/api"
ADMIN_API="$API_BASE/admin"
SMS_API="$API_BASE/sms"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper function to parse JSON with Python
json_val() {
    python3 -c "import sys, json; print(json.load(sys.stdin)$1)" 2>/dev/null
}

echo -e "${YELLOW}==================================================${NC}"
echo -e "${YELLOW}SMPP-MLS Message Timeout & Escalation Test${NC}"
echo -e "${YELLOW}==================================================${NC}"
echo ""

# Check dependencies
if ! command -v python3 &> /dev/null; then
    echo -e "${RED}❌ Error: python3 is required but not installed.${NC}"
    exit 1
fi

# 1. Get an active session
echo "Step 1: Finding an active session..."
SESSIONS=$(curl -s "$ADMIN_API/sessions")
SESSION_ID=$(echo "$SESSIONS" | json_val "[0]['sessionId']")

if [ -z "$SESSION_ID" ] || [ "$SESSION_ID" == "None" ]; then
    echo -e "${RED}❌ Error: No sessions found! Is the application running?${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Found session: $SESSION_ID${NC}"
echo ""

# 2. Stop the session
echo "Step 2: Stopping session to simulate 'No Connector'..."
STOP_RESP=$(curl -s -X POST "$ADMIN_API/session/$SESSION_ID/stop")
SUCCESS=$(echo "$STOP_RESP" | json_val "['success']")

if [ "$SUCCESS" != "True" ]; then
    echo -e "${RED}❌ Failed to stop session: $STOP_RESP${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Session stopped successfully${NC}"
echo ""

# 3. Send a NORMAL priority message
echo "Step 3: Sending NORMAL priority message..."
MSISDN="999000$(date +%s | tail -c 4)" # Randomish MSISDN
MSG_PAYLOAD="{\"msisdn\": \"$MSISDN\", \"message\": \"Timeout Test $(date)\", \"priority\": \"NORMAL\"}"

SEND_RESP=$(curl -s -X POST -H "Content-Type: application/json" -d "$MSG_PAYLOAD" "$SMS_API/send")
MSG_ID=$(echo "$SEND_RESP" | json_val "['messageId']")

if [ -z "$MSG_ID" ] || [ "$MSG_ID" == "None" ]; then
    echo -e "${RED}❌ Failed to send message: $SEND_RESP${NC}"
    # Try to restart session before exiting
    curl -s -X POST "$ADMIN_API/session/$SESSION_ID/start" > /dev/null
    exit 1
fi

echo -e "${GREEN}✅ Message sent! ID: $MSG_ID${NC}"
echo ""

# 4. Verify Initial State
echo "Step 4: Verifying initial state (QUEUED / NORMAL)..."
sleep 2 # Give it a moment to appear in activity

ACTIVITY=$(curl -s "$ADMIN_API/activity")
# Find our message in activity list using Python list comprehension
# Keys are uppercase from JdbcTemplate
MSG_DATA=$(echo "$ACTIVITY" | python3 -c "import sys, json; 
data = json.load(sys.stdin); 
msg = next((item for item in data if item.get('ID') == $MSG_ID), None); 
print(json.dumps(msg) if msg else '{}')")

STATUS=$(echo "$MSG_DATA" | json_val "['STATUS']")
PRIORITY=$(echo "$MSG_DATA" | json_val "['PRIORITY']")

echo "   Current Status: $STATUS"
echo "   Current Priority: $PRIORITY"

if [ "$STATUS" != "QUEUED" ]; then
    echo -e "${RED}❌ Unexpected status: $STATUS (Expected: QUEUED)${NC}"
    # Restart session
    curl -s -X POST "$ADMIN_API/session/$SESSION_ID/start" > /dev/null
    exit 1
fi

if [ "$PRIORITY" != "NORMAL" ]; then
    echo -e "${RED}❌ Unexpected priority: $PRIORITY (Expected: NORMAL)${NC}"
    # Restart session
    curl -s -X POST "$ADMIN_API/session/$SESSION_ID/start" > /dev/null
    exit 1
fi

echo -e "${GREEN}✅ Initial state verified${NC}"
echo ""

# 5. Wait for timeout (60s threshold)
echo "Step 5: Waiting 70 seconds for timeout escalation..."
for i in {1..70}; do
    echo -ne "   Waiting... ${i}s\r"
    sleep 1
done
echo ""
echo ""

# 6. Verify Final State (Escalation)
echo "Step 6: Verifying escalation (Priority -> HIGH)..."
ACTIVITY=$(curl -s "$ADMIN_API/activity")
MSG_DATA=$(echo "$ACTIVITY" | python3 -c "import sys, json; 
data = json.load(sys.stdin); 
msg = next((item for item in data if item.get('ID') == $MSG_ID), None); 
print(json.dumps(msg) if msg else '{}')")

NEW_PRIORITY=$(echo "$MSG_DATA" | json_val "['PRIORITY']")

echo "   New Priority: $NEW_PRIORITY"

if [ "$NEW_PRIORITY" == "HIGH" ]; then
    echo -e "${GREEN}✅ Priority escalated to HIGH!${NC}"
else
    echo -e "${RED}❌ Priority did NOT escalate! Still: $NEW_PRIORITY${NC}"
fi
echo ""

# 7. Verify Alert
echo "Step 7: Verifying dashboard alert..."
ALERTS=$(curl -s "$ADMIN_API/alerts")
# Check for alert related to our message ID
ALERT_TYPE=$(echo "$ALERTS" | python3 -c "import sys, json; 
data = json.load(sys.stdin); 
alert = next((item for item in data if item.get('messageId') == $MSG_ID), None); 
print(alert['type'] if alert else 'None')")

if [ "$ALERT_TYPE" == "MESSAGE_DELAYED" ]; then
    echo -e "${GREEN}✅ Alert found: MESSAGE_DELAYED${NC}"
else
    echo -e "${RED}❌ Alert NOT found!${NC}"
    echo "   Active alerts:"
    echo "$ALERTS" | python3 -m json.tool
fi
echo ""

# 8. Cleanup
echo "Step 8: Restarting session..."
START_RESP=$(curl -s -X POST "$ADMIN_API/session/$SESSION_ID/start")
SUCCESS=$(echo "$START_RESP" | json_val "['success']")

if [ "$SUCCESS" == "True" ]; then
    echo -e "${GREEN}✅ Session restarted successfully${NC}"
else
    echo -e "${RED}❌ Failed to restart session: $START_RESP${NC}"
fi

echo ""
echo -e "${YELLOW}==================================================${NC}"
echo -e "${YELLOW}Test Complete${NC}"
echo -e "${YELLOW}==================================================${NC}"
