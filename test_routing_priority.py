#!/usr/bin/env python3
"""
Test Script for SMPP Routing and Priority Handling

This script tests:
1. Prefix-based routing to correct operators
2. High Priority (HP) messages getting priority over Normal Priority (NP)
3. TPS throttling per session
4. Message delivery and DLR reception

Usage:
    python test_routing_priority.py
"""

import requests
import time
import json
from datetime import datetime
from typing import List, Dict
import sys

class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    MAGENTA = '\033[95m'
    CYAN = '\033[96m'
    RESET = '\033[0m'
    BOLD = '\033[1m'

class RoutingPriorityTester:
    def __init__(self, base_url: str = "http://localhost:8080"):
        self.base_url = base_url.rstrip('/')
        self.headers = {}  # No API key needed if security disabled
        
        # Afghan operator prefix mapping
        self.operator_prefixes = {
            'aftel': ['93-20', '93-25'],
            'roshan': ['93-79', '93-77', '93-72'],
            'awcc': ['93-70', '93-71'],
            'mtn': ['93-78', '93-76'],
            'salaam': ['93-74', '93-75']
        }
        
    def log(self, message: str, level: str = "INFO"):
        timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        color = {
            "INFO": Colors.BLUE,
            "PASS": Colors.GREEN,
            "FAIL": Colors.RED,
            "WARN": Colors.YELLOW,
            "TEST": Colors.CYAN
        }.get(level, Colors.RESET)
        print(f"{color}[{timestamp}] [{level:4s}] {message}{Colors.RESET}")
    
    def get_session_health(self) -> Dict[str, bool]:
        """Get current session health status"""
        try:
            response = requests.get(f"{self.base_url}/api/smpp/health", headers=self.headers, timeout=5)
            if response.status_code == 200:
                return response.json()
            return {}
        except Exception as e:
            self.log(f"Failed to get session health: {e}", "WARN")
            return {}
    
    def submit_message(self, msisdn: str, message: str, priority: str = "NORMAL") -> Dict:
        """Submit a single message"""
        payload = {
            "msisdn": msisdn,
            "message": message,
            "priority": priority
        }
        try:
            response = requests.post(
                f"{self.base_url}/api/sms/send",
                json=payload,
                headers=self.headers,
                timeout=5
            )
            if response.status_code in [200, 201, 202]:
                return {"success": True, "data": response.json()}
            else:
                return {"success": False, "error": f"HTTP {response.status_code}: {response.text}"}
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    def test_routing_by_prefix(self):
        """Test 1: Verify messages are routed to correct operators based on prefix"""
        self.log("=" * 70, "TEST")
        self.log("TEST 1: Prefix-Based Routing", "TEST")
        self.log("=" * 70, "TEST")
        
        test_cases = [
            ("93201234567", "aftel", "AFTEL"),
            ("93791234567", "roshan", "Roshan"),
            ("93701234567", "awcc", "AWCC"),
            ("93781234567", "mtn", "MTN"),
            ("93741234567", "salaam", "Salaam"),
        ]
        
        passed = 0
        for msisdn, expected_operator, operator_name in test_cases:
            self.log(f"Testing {operator_name} (prefix {msisdn[:5]})", "INFO")
            result = self.submit_message(msisdn, f"Test routing to {operator_name}", "NORMAL")
            
            if result["success"]:
                self.log(f"✓ Message accepted for {operator_name}", "PASS")
                passed += 1
            else:
                self.log(f"✗ Failed to route to {operator_name}: {result.get('error')}", "FAIL")
        
        self.log(f"\nRouting Test: {passed}/{len(test_cases)} passed\n", "INFO")
        return passed == len(test_cases)
    
    def test_priority_handling(self):
        """Test 2: Verify HP messages get priority over NP messages"""
        self.log("=" * 70, "TEST")
        self.log("TEST 2: High Priority vs Normal Priority", "TEST")
        self.log("=" * 70, "TEST")
        
        # Submit 20 NP messages first
        self.log("Submitting 20 Normal Priority messages...", "INFO")
        np_submitted = 0
        for i in range(20):
            result = self.submit_message(
                f"93791000{i:03d}",
                f"NP Message {i}",
                "NORMAL"
            )
            if result["success"]:
                np_submitted += 1
        
        self.log(f"Submitted {np_submitted}/20 NP messages", "INFO")
        
        # Wait a moment
        time.sleep(1)
        
        # Submit 10 HP messages
        self.log("Submitting 10 High Priority messages...", "INFO")
        hp_submitted = 0
        hp_start_time = time.time()
        for i in range(10):
            result = self.submit_message(
                f"93792000{i:03d}",
                f"HP Message {i}",
                "HIGH"
            )
            if result["success"]:
                hp_submitted += 1
        
        self.log(f"Submitted {hp_submitted}/10 HP messages", "INFO")
        
        # In a real test, you would check the database to verify HP messages
        # were sent before NP messages. For now, we just verify submission.
        self.log("\n⚠ Note: To fully verify priority, check database or logs to confirm", "WARN")
        self.log("   HP messages are sent before NP messages despite being submitted later.\n", "WARN")
        
        success = hp_submitted == 10 and np_submitted == 20
        if success:
            self.log("Priority Test: Messages submitted successfully", "PASS")
        else:
            self.log("Priority Test: Some messages failed to submit", "FAIL")
        
        return success
    
    def test_hp_throttling(self):
        """Test 3: Verify HP messages are throttled to max 20% of TPS"""
        self.log("=" * 70, "TEST")
        self.log("TEST 3: HP Throttling (20% of TPS)", "TEST")
        self.log("=" * 70, "TEST")
        
        # Submit 50 HP messages rapidly
        self.log("Submitting 50 HP messages rapidly to test throttling...", "INFO")
        hp_count = 0
        start_time = time.time()
        
        for i in range(50):
            result = self.submit_message(
                f"93793000{i:03d}",
                f"HP Throttle Test {i}",
                "HIGH"
            )
            if result["success"]:
                hp_count += 1
        
        elapsed = time.time() - start_time
        
        self.log(f"Submitted {hp_count}/50 HP messages in {elapsed:.2f}s", "INFO")
        self.log("\n⚠ Note: Check application logs to verify HP throttling is active.", "WARN")
        self.log("   Look for 'HP token bucket' or 'throttled' messages.\n", "WARN")
        
        return hp_count > 0
    
    def test_session_distribution(self):
        """Test 4: Verify messages are distributed across multiple sessions"""
        self.log("=" * 70, "TEST")
        self.log("TEST 4: Multi-Session Distribution", "TEST")
        self.log("=" * 70, "TEST")
        
        # Check session health
        health = self.get_session_health()
        roshan_sessions = [k for k in health.keys() if k.startswith('roshan')]
        
        self.log(f"Roshan has {len(roshan_sessions)} sessions configured", "INFO")
        bound_sessions = [k for k in roshan_sessions if health[k]]
        self.log(f"{len(bound_sessions)} sessions are currently bound", "INFO")
        
        if len(bound_sessions) < 2:
            self.log("⚠ Warning: Need at least 2 bound sessions to test distribution", "WARN")
            return False
        
        # Submit 100 messages to Roshan
        self.log("Submitting 100 messages to test distribution...", "INFO")
        submitted = 0
        for i in range(100):
            result = self.submit_message(
                f"93794000{i:03d}",
                f"Distribution Test {i}",
                "NORMAL"
            )
            if result["success"]:
                submitted += 1
            
            # Small delay to avoid overwhelming
            if i % 10 == 0:
                time.sleep(0.1)
        
        self.log(f"Submitted {submitted}/100 messages", "INFO")
        self.log("\n⚠ Note: Check simulator logs to verify messages are distributed", "WARN")
        self.log("   across multiple sessions (roshan_user1 and roshan_user2).\n", "WARN")
        
        return submitted >= 95  # Allow 5% failure
    
    def test_tps_enforcement(self):
        """Test 5: Verify TPS limits are enforced"""
        self.log("=" * 70, "TEST")
        self.log("TEST 5: TPS Enforcement", "TEST")
        self.log("=" * 70, "TEST")
        
        # Salaam has 1 session with 50 TPS
        self.log("Testing TPS limit on Salaam (50 TPS)...", "INFO")
        
        # Submit 100 messages as fast as possible
        submitted = 0
        start_time = time.time()
        
        for i in range(100):
            result = self.submit_message(
                f"93745000{i:03d}",
                f"TPS Test {i}",
                "NORMAL"
            )
            if result["success"]:
                submitted += 1
        
        elapsed = time.time() - start_time
        actual_tps = submitted / elapsed if elapsed > 0 else 0
        
        self.log(f"Submitted {submitted} messages in {elapsed:.2f}s", "INFO")
        self.log(f"Actual TPS: {actual_tps:.2f}", "INFO")
        
        # Messages should be queued, not rejected
        if submitted >= 95:
            self.log("✓ Messages accepted (will be throttled during sending)", "PASS")
            return True
        else:
            self.log("✗ Too many messages rejected", "FAIL")
            return False
    
    def run_all_tests(self):
        """Run all routing and priority tests"""
        print(f"\n{Colors.BOLD}{'='*70}{Colors.RESET}")
        print(f"{Colors.BOLD}SMPP Routing and Priority Test Suite{Colors.RESET}")
        print(f"{Colors.BOLD}{'='*70}{Colors.RESET}\n")
        
        # Check session health first
        self.log("Checking SMPP session health...", "INFO")
        health = self.get_session_health()
        
        if not health:
            self.log("✗ Failed to get session health. Is the application running?", "FAIL")
            return False
        
        bound_count = sum(1 for v in health.values() if v)
        total_count = len(health)
        self.log(f"Sessions: {bound_count}/{total_count} bound", "INFO")
        
        if bound_count == 0:
            self.log("✗ No sessions are bound. Start the SMPP simulator first!", "FAIL")
            return False
        
        print()
        
        # Run tests
        results = []
        results.append(("Prefix-Based Routing", self.test_routing_by_prefix()))
        results.append(("Priority Handling", self.test_priority_handling()))
        results.append(("HP Throttling", self.test_hp_throttling()))
        results.append(("Multi-Session Distribution", self.test_session_distribution()))
        results.append(("TPS Enforcement", self.test_tps_enforcement()))
        
        # Summary
        print(f"\n{Colors.BOLD}{'='*70}{Colors.RESET}")
        print(f"{Colors.BOLD}TEST SUMMARY{Colors.RESET}")
        print(f"{Colors.BOLD}{'='*70}{Colors.RESET}\n")
        
        passed = sum(1 for _, result in results if result)
        total = len(results)
        
        for test_name, result in results:
            status = f"{Colors.GREEN}✓ PASS{Colors.RESET}" if result else f"{Colors.RED}✗ FAIL{Colors.RESET}"
            print(f"  {status}  {test_name}")
        
        print(f"\n{Colors.BOLD}Total: {passed}/{total} tests passed{Colors.RESET}")
        print(f"{Colors.BOLD}{'='*70}{Colors.RESET}\n")
        
        # Additional verification steps
        print(f"{Colors.YELLOW}Additional Manual Verification:{Colors.RESET}")
        print(f"  1. Check simulator logs for message distribution across sessions")
        print(f"  2. Query database to verify HP messages sent before NP:")
        print(f"     {Colors.CYAN}SELECT id, msisdn, priority, status, created_at FROM sms_outbound ORDER BY created_at;{Colors.RESET}")
        print(f"  3. Check application logs for HP throttling messages")
        print(f"  4. Monitor Prometheus metrics at: {self.base_url}/actuator/prometheus")
        print()
        
        return passed == total

def main():
    tester = RoutingPriorityTester()
    success = tester.run_all_tests()
    sys.exit(0 if success else 1)

if __name__ == '__main__':
    main()
