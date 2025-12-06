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
echo -e "${YELLOW}SMPP-MLS Delayed Message Tracking Test${NC}"
echo -e "${YELLOW}==================================================${NC}"
echo ""

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
echo "Step 2: Stopping session to simulate delay..."
STOP_RESP=$(curl -s -X POST "$ADMIN_API/session/$SESSION_ID/stop")
SUCCESS=$(echo "$STOP_RESP" | json_val "['success']")

if [ "$SUCCESS" != "True" ]; then
    echo -e "${RED}❌ Failed to stop session: $STOP_RESP${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Session stopped successfully${NC}"
echo ""

# 3. Send a message
echo "Step 3: Sending message..."
MSISDN="999000$(date +%s | tail -c 4)"
MSG_PAYLOAD="{\"msisdn\": \"$MSISDN\", \"message\": \"Delayed Tracking Test $(date)\", \"priority\": \"NORMAL\"}"

SEND_RESP=$(curl -s -X POST -H "Content-Type: application/json" -d "$MSG_PAYLOAD" "$SMS_API/send")
MSG_ID=$(echo "$SEND_RESP" | json_val "['messageId']")

if [ -z "$MSG_ID" ] || [ "$MSG_ID" == "None" ]; then
    echo -e "${RED}❌ Failed to send message: $SEND_RESP${NC}"
    curl -s -X POST "$ADMIN_API/session/$SESSION_ID/start" > /dev/null
    exit 1
fi

echo -e "${GREEN}✅ Message sent! ID: $MSG_ID${NC}"
echo ""

# 4. Wait for > 60 seconds
echo "Step 4: Waiting 65 seconds to exceed threshold..."
for i in {1..65}; do
    echo -ne "   Waiting... ${i}s\r"
    sleep 1
done
echo ""
echo ""

# 5. Start the session (Trigger exit)
echo "Step 5: Restarting session to allow message exit..."
START_RESP=$(curl -s -X POST "$ADMIN_API/session/$SESSION_ID/start")
SUCCESS=$(echo "$START_RESP" | json_val "['success']")

if [ "$SUCCESS" == "True" ]; then
    echo -e "${GREEN}✅ Session restarted successfully${NC}"
else
    echo -e "${RED}❌ Failed to restart session: $START_RESP${NC}"
    exit 1
fi

# 6. Wait for processing
echo "Step 6: Waiting 5 seconds for message processing..."
sleep 5
echo ""

# 7. Verify Log
echo "Step 7: Verifying delayed message log..."
LOGS=$(curl -s "$ADMIN_API/delayed-messages")
# Check if our message ID is in the logs
LOG_ENTRY=$(echo "$LOGS" | python3 -c "import sys, json; 
data = json.load(sys.stdin); 
entry = next((item for item in data if item['originalMessageId'] == $MSG_ID), None); 
print(json.dumps(entry) if entry else '{}')")

FOUND_ID=$(echo "$LOG_ENTRY" | json_val "['originalMessageId']")
DURATION=$(echo "$LOG_ENTRY" | json_val "['durationSeconds']")

if [ "$FOUND_ID" == "$MSG_ID" ]; then
    echo -e "${GREEN}✅ SUCCESS! Delayed message recorded.${NC}"
    echo "   Original ID: $FOUND_ID"
    echo "   Duration: ${DURATION}s"
else
    echo -e "${RED}❌ FAILURE! Message not found in delayed logs.${NC}"
    echo "   Logs content:"
    echo "$LOGS" | python3 -m json.tool
fi

echo ""
echo -e "${YELLOW}==================================================${NC}"
echo -e "${YELLOW}Test Complete${NC}"
echo -e "${YELLOW}==================================================${NC}"
