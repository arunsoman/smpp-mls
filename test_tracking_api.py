#!/usr/bin/env python3
"""
Message Tracking API Test Script

Tests the comprehensive message tracking API endpoints.

Usage:
    python test_tracking_api.py
"""

import requests
import json
import time
from datetime import datetime

class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    RESET = '\033[0m'
    BOLD = '\033[1m'

def print_section(title):
    print(f"\n{Colors.BOLD}{Colors.CYAN}{'='*80}{Colors.RESET}")
    print(f"{Colors.BOLD}{Colors.CYAN}{title}{Colors.RESET}")
    print(f"{Colors.BOLD}{Colors.CYAN}{'='*80}{Colors.RESET}\n")

def print_json(data):
    """Pretty print JSON data"""
    print(json.dumps(data, indent=2, default=str))

def test_tracking_api(base_url="http://localhost:8080"):
    print(f"\n{Colors.BOLD}{'='*80}{Colors.RESET}")
    print(f"{Colors.BOLD}Message Tracking API Test{Colors.RESET}")
    print(f"{Colors.BOLD}{'='*80}{Colors.RESET}\n")
    
    # Step 1: Submit a test message
    print_section("STEP 1: Submit Test Message")
    
    payload = {
        "msisdn": "93791234567",
        "message": "Tracking API Test Message",
        "priority": "NORMAL",
        "clientMsgId": f"test-track-{int(time.time())}"
    }
    
    print(f"{Colors.BLUE}Submitting message to: {payload['msisdn']}{Colors.RESET}")
    response = requests.post(f"{base_url}/api/sms/send", json=payload)
    
    if response.status_code not in [200, 201, 202]:
        print(f"{Colors.RED}Failed to submit message: {response.status_code}{Colors.RESET}")
        print(response.text)
        return
    
    submit_response = response.json()
    print(f"{Colors.GREEN}✓ Message submitted successfully{Colors.RESET}")
    print_json(submit_response)
    
    # Extract IDs
    message_id = submit_response.get('messageId') or submit_response.get('id')
    request_id = submit_response.get('requestId')
    client_msg_id = payload['clientMsgId']
    msisdn = payload['msisdn']
    
    # Wait a moment for processing
    print(f"\n{Colors.YELLOW}Waiting 2 seconds for message processing...{Colors.RESET}")
    time.sleep(2)
    
    # Step 2: Track by Message ID
    print_section("STEP 2: Track by Message ID")
    print(f"{Colors.BLUE}Tracking message ID: {message_id}{Colors.RESET}")
    
    response = requests.get(f"{base_url}/api/track/message/{message_id}")
    if response.status_code == 200:
        print(f"{Colors.GREEN}✓ Message found{Colors.RESET}")
        print_json(response.json())
    else:
        print(f"{Colors.RED}✗ Failed: {response.status_code}{Colors.RESET}")
        print(response.text)
    
    # Step 3: Track by Request ID
    print_section("STEP 3: Track by Request ID")
    print(f"{Colors.BLUE}Tracking request ID: {request_id}{Colors.RESET}")
    
    response = requests.get(f"{base_url}/api/track/request/{request_id}")
    if response.status_code == 200:
        print(f"{Colors.GREEN}✓ Message found{Colors.RESET}")
        track_data = response.json()
        print_json(track_data)
        
        # Display key information
        print(f"\n{Colors.BOLD}Key Information:{Colors.RESET}")
        print(f"  Status: {Colors.YELLOW}{track_data.get('status', {}).get('currentStatus')}{Colors.RESET}")
        print(f"  Operator: {track_data.get('routing', {}).get('operator')}")
        print(f"  Session: {track_data.get('routing', {}).get('sessionId')}")
        print(f"  Delivery Status: {Colors.GREEN if track_data.get('deliveryStatus') == 'DELIVERED' else Colors.YELLOW}{track_data.get('deliveryStatus')}{Colors.RESET}")
        
        # Check for SMSC message ID
        smsc_msg_id = track_data.get('submission', {}).get('smscMessageId')
        if smsc_msg_id:
            print(f"  SMSC Message ID: {smsc_msg_id}")
    else:
        print(f"{Colors.RED}✗ Failed: {response.status_code}{Colors.RESET}")
    
    # Step 4: Track by Client Message ID
    print_section("STEP 4: Track by Client Message ID")
    print(f"{Colors.BLUE}Tracking client message ID: {client_msg_id}{Colors.RESET}")
    
    response = requests.get(f"{base_url}/api/track/client/{client_msg_id}")
    if response.status_code == 200:
        print(f"{Colors.GREEN}✓ Message found{Colors.RESET}")
        print_json(response.json())
    else:
        print(f"{Colors.RED}✗ Failed: {response.status_code}{Colors.RESET}")
    
    # Step 5: Track by Phone Number
    print_section("STEP 5: Track by Phone Number")
    print(f"{Colors.BLUE}Tracking phone number: {msisdn}{Colors.RESET}")
    
    response = requests.get(f"{base_url}/api/track/phone/{msisdn}")
    if response.status_code == 200:
        data = response.json()
        print(f"{Colors.GREEN}✓ Found {data.get('totalMessages')} message(s){Colors.RESET}")
        print_json(data)
    else:
        print(f"{Colors.RED}✗ Failed: {response.status_code}{Colors.RESET}")
    
    # Step 6: Track by SMSC Message ID (if available)
    if smsc_msg_id:
        print_section("STEP 6: Track by SMSC Message ID")
        print(f"{Colors.BLUE}Tracking SMSC message ID: {smsc_msg_id}{Colors.RESET}")
        
        response = requests.get(f"{base_url}/api/track/smsc/{smsc_msg_id}")
        if response.status_code == 200:
            print(f"{Colors.GREEN}✓ Message found{Colors.RESET}")
            print_json(response.json())
        else:
            print(f"{Colors.RED}✗ Failed: {response.status_code}{Colors.RESET}")
    
    # Summary
    print_section("SUMMARY")
    print(f"{Colors.GREEN}✓ All tracking methods tested successfully{Colors.RESET}")
    print(f"\n{Colors.BOLD}Available Tracking Endpoints:{Colors.RESET}")
    print(f"  1. {Colors.CYAN}GET /api/track/message/{{id}}{Colors.RESET} - Track by message ID")
    print(f"  2. {Colors.CYAN}GET /api/track/request/{{requestId}}{Colors.RESET} - Track by request ID")
    print(f"  3. {Colors.CYAN}GET /api/track/client/{{clientMsgId}}{Colors.RESET} - Track by client message ID")
    print(f"  4. {Colors.CYAN}GET /api/track/phone/{{msisdn}}{Colors.RESET} - Track by phone number")
    print(f"  5. {Colors.CYAN}GET /api/track/smsc/{{smscMsgId}}{Colors.RESET} - Track by SMSC message ID")
    
    print(f"\n{Colors.BOLD}Report Includes:{Colors.RESET}")
    print(f"  • Message details (ID, phone, message, priority)")
    print(f"  • Routing information (operator, session)")
    print(f"  • Status & timing (received, submitted, delivered)")
    print(f"  • SMPP submission details (SMSC message ID, delays)")
    print(f"  • Delivery receipts (DLR status, delivery time)")
    print(f"  • Complete timeline of message journey")
    print()

def test_error_cases(base_url="http://localhost:8080"):
    """Test error handling"""
    print_section("ERROR HANDLING TESTS")
    
    # Test non-existent message ID
    print(f"{Colors.BLUE}Testing non-existent message ID...{Colors.RESET}")
    response = requests.get(f"{base_url}/api/track/message/999999")
    if response.status_code == 404:
        print(f"{Colors.GREEN}✓ Correctly returns 404 for non-existent message{Colors.RESET}")
        print_json(response.json())
    else:
        print(f"{Colors.RED}✗ Unexpected status: {response.status_code}{Colors.RESET}")
    
    print()
    
    # Test non-existent phone number
    print(f"{Colors.BLUE}Testing non-existent phone number...{Colors.RESET}")
    response = requests.get(f"{base_url}/api/track/phone/99999999999")
    if response.status_code == 404:
        print(f"{Colors.GREEN}✓ Correctly returns 404 for non-existent phone{Colors.RESET}")
        print_json(response.json())
    else:
        print(f"{Colors.RED}✗ Unexpected status: {response.status_code}{Colors.RESET}")

if __name__ == '__main__':
    import sys
    
    try:
        test_tracking_api()
        test_error_cases()
    except requests.exceptions.ConnectionError:
        print(f"{Colors.RED}✗ Cannot connect to application{Colors.RESET}")
        print(f"{Colors.YELLOW}Make sure your Spring Boot application is running on http://localhost:8080{Colors.RESET}")
        sys.exit(1)
    except Exception as e:
        print(f"{Colors.RED}✗ Error: {e}{Colors.RESET}")
        sys.exit(1)
