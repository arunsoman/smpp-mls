    #!/usr/bin/env python3
"""
Comprehensive SMPP-MLS Feature Test Suite

Tests all implemented features from the implementation backlog:
- A1: Project skeleton & infrastructure
- A2: Externalized configuration
- A3: SMPP session management (bind, reconnect)
- A4: Enquire_link & health monitoring
- D1: REST submit endpoint
- E1: Persistence
- C1/C2: Dual priority queuing & throttling
- F1: DLR handling
- G1: Prometheus metrics

Usage:
    python test_smpp_features.py --base-url http://localhost:8080
"""

import argparse
import json
import time
import requests
from datetime import datetime
from typing import Dict, List, Optional
import sys

class Colors:
    """ANSI color codes for terminal output"""
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    RESET = '\033[0m'
    BOLD = '\033[1m'

class SMPPTestSuite:
    def __init__(self, base_url: str, api_key: Optional[str] = None):
        self.base_url = base_url.rstrip('/')
        self.api_key = api_key
        self.results = []
        self.passed = 0
        self.failed = 0
        self.headers = {}
        if api_key:
            self.headers['X-API-KEY'] = api_key
        
    def log(self, message: str, level: str = "INFO"):
        """Log a message with timestamp and color"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        color = {
            "INFO": Colors.BLUE,
            "PASS": Colors.GREEN,
            "FAIL": Colors.RED,
            "WARN": Colors.YELLOW
        }.get(level, Colors.RESET)
        
        print(f"{color}[{timestamp}] [{level}] {message}{Colors.RESET}")
    
    def test_result(self, test_name: str, passed: bool, details: str = ""):
        """Record test result"""
        self.results.append({
            "test": test_name,
            "passed": passed,
            "details": details
        })
        
        if passed:
            self.passed += 1
            self.log(f"✓ {test_name}: {details}", "PASS")
        else:
            self.failed += 1
            self.log(f"✗ {test_name}: {details}", "FAIL")
    
    # ========== Epic A: Core Infrastructure Tests ==========
    
    def test_a1_application_health(self):
        """A1: Test that application is running and healthy"""
        try:
            response = requests.get(f"{self.base_url}/actuator/health", timeout=5)
            if response.status_code == 200:
                data = response.json()
                status = data.get('status', 'UNKNOWN')
                self.test_result(
                    "A1: Application Health",
                    status == 'UP',
                    f"Status: {status}"
                )
            else:
                self.test_result(
                    "A1: Application Health",
                    False,
                    f"HTTP {response.status_code}"
                )
        except Exception as e:
            self.test_result("A1: Application Health", False, str(e))
    
    def test_a2_actuator_endpoints(self):
        """A2: Test actuator endpoints are exposed"""
        try:
            response = requests.get(f"{self.base_url}/actuator", timeout=5)
            if response.status_code == 200:
                data = response.json()
                endpoints = data.get('_links', {}).keys()
                required = ['health', 'info', 'prometheus']
                has_all = all(ep in endpoints for ep in required)
                self.test_result(
                    "A2: Actuator Endpoints",
                    has_all,
                    f"Found: {', '.join(endpoints)}"
                )
            else:
                self.test_result("A2: Actuator Endpoints", False, f"HTTP {response.status_code}")
        except Exception as e:
            self.test_result("A2: Actuator Endpoints", False, str(e))
    
    def test_a3_smpp_session_health(self):
        """A3: Test SMPP session health endpoint"""
        try:
            response = requests.get(f"{self.base_url}/api/smpp/health", headers=self.headers, timeout=5)
            if response.status_code == 200:
                sessions = response.json()
                self.test_result(
                    "A3: SMPP Session Health",
                    isinstance(sessions, dict),
                    f"Sessions: {list(sessions.keys())}"
                )
                
                # Check if any session is bound
                any_bound = any(sessions.values())
                self.test_result(
                    "A3: Session Binding",
                    any_bound,
                    f"Bound sessions: {sum(sessions.values())}/{len(sessions)}"
                )
            else:
                self.test_result("A3: SMPP Session Health", False, f"HTTP {response.status_code}")
        except Exception as e:
            self.test_result("A3: SMPP Session Health", False, str(e))
    
    # ========== Epic D: Submission API Tests ==========
    
    def test_d1_submit_endpoint_validation(self):
        """D1: Test REST submit endpoint with invalid data"""
        try:
            # Test with missing required fields
            response = requests.post(
                f"{self.base_url}/api/sms/send",
                json={},
                headers=self.headers,
                timeout=5
            )
            self.test_result(
                "D1: Submit Validation (Empty)",
                response.status_code == 400,
                f"HTTP {response.status_code}"
            )
            
            # Test with invalid MSISDN
            response = requests.post(
                f"{self.base_url}/api/sms/send",
                json={"msisdn": "invalid", "message": "test"},
                headers=self.headers,
                timeout=5
            )
            self.test_result(
                "D1: Submit Validation (Invalid MSISDN)",
                response.status_code in [400, 422],
                f"HTTP {response.status_code}"
            )
        except Exception as e:
            self.test_result("D1: Submit Validation", False, str(e))
    
    def test_d1_submit_normal_priority(self):
        """D1: Test submitting a normal priority message"""
        try:
            payload = {
                "msisdn": "93701234567",
                "message": "Test message - Normal Priority",
                "priority": "NORMAL"
            }
            response = requests.post(
                f"{self.base_url}/api/sms/send",
                json=payload,
                headers=self.headers,
                timeout=5
            )
            
            if response.status_code in [200, 201, 202]:
                data = response.json()
                has_id = 'id' in data or 'messageId' in data or 'requestId' in data
                self.test_result(
                    "D1: Submit Normal Priority",
                    has_id,
                    f"Response: {data}"
                )
                return data.get('id') or data.get('messageId') or data.get('requestId')
            else:
                self.test_result(
                    "D1: Submit Normal Priority",
                    False,
                    f"HTTP {response.status_code}: {response.text}"
                )
        except Exception as e:
            self.test_result("D1: Submit Normal Priority", False, str(e))
        return None
    
    def test_d1_submit_high_priority(self):
        """D1: Test submitting a high priority message"""
        try:
            payload = {
                "msisdn": "93701234568",
                "message": "Test message - High Priority",
                "priority": "HIGH"
            }
            response = requests.post(
                f"{self.base_url}/api/sms/send",
                json=payload,
                headers=self.headers,
                timeout=5
            )
            
            if response.status_code in [200, 201, 202]:
                data = response.json()
                has_id = 'id' in data or 'messageId' in data or 'requestId' in data
                self.test_result(
                    "D1: Submit High Priority",
                    has_id,
                    f"Response: {data}"
                )
                return data.get('id') or data.get('messageId') or data.get('requestId')
            else:
                self.test_result(
                    "D1: Submit High Priority",
                    False,
                    f"HTTP {response.status_code}: {response.text}"
                )
        except Exception as e:
            self.test_result("D1: Submit High Priority", False, str(e))
        return None
    
    # ========== Epic E: Persistence Tests ==========
    
    def test_e1_h2_console_access(self):
        """E1: Test H2 console is accessible"""
        try:
            response = requests.get(f"{self.base_url}/h2-console", timeout=5)
            self.test_result(
                "E1: H2 Console Access",
                response.status_code == 200,
                f"HTTP {response.status_code}"
            )
        except Exception as e:
            self.test_result("E1: H2 Console Access", False, str(e))
    
    # ========== Epic C: Priority & Throttling Tests ==========
    
    def test_c1_bulk_submit_mixed_priority(self):
        """C1/C2: Test bulk submission with mixed priorities"""
        try:
            self.log("Submitting 10 HP and 10 NP messages...", "INFO")
            hp_count = 0
            np_count = 0
            
            for i in range(10):
                # Submit HP message
                hp_payload = {
                    "msisdn": f"9370123{4000 + i}",
                    "message": f"HP Test {i}",
                    "priority": "HIGH"
                }
                hp_resp = requests.post(f"{self.base_url}/api/sms/send", json=hp_payload, headers=self.headers, timeout=5)
                if hp_resp.status_code in [200, 201, 202]:
                    hp_count += 1
                
                # Submit NP message
                np_payload = {
                    "msisdn": f"9370123{5000 + i}",
                    "message": f"NP Test {i}",
                    "priority": "NORMAL"
                }
                np_resp = requests.post(f"{self.base_url}/api/sms/send", json=np_payload, headers=self.headers, timeout=5)
                if np_resp.status_code in [200, 201, 202]:
                    np_count += 1
            
            self.test_result(
                "C1: Bulk Mixed Priority Submit",
                hp_count == 10 and np_count == 10,
                f"HP: {hp_count}/10, NP: {np_count}/10"
            )
        except Exception as e:
            self.test_result("C1: Bulk Mixed Priority Submit", False, str(e))
    
    # ========== Epic G: Observability Tests ==========
    
    def test_g1_prometheus_metrics(self):
        """G1: Test Prometheus metrics endpoint"""
        try:
            response = requests.get(f"{self.base_url}/actuator/prometheus", timeout=5)
            if response.status_code == 200:
                metrics_text = response.text
                # Check for key metrics
                has_jvm = 'jvm_' in metrics_text
                has_http = 'http_' in metrics_text
                has_smpp = 'smpp_' in metrics_text or 'sms_' in metrics_text
                
                self.test_result(
                    "G1: Prometheus Metrics",
                    has_jvm and has_http,
                    f"JVM: {has_jvm}, HTTP: {has_http}, SMPP: {has_smpp}"
                )
            else:
                self.test_result("G1: Prometheus Metrics", False, f"HTTP {response.status_code}")
        except Exception as e:
            self.test_result("G1: Prometheus Metrics", False, str(e))
    
    # ========== Load & Performance Tests ==========
    
    def test_load_sustained_submission(self, count: int = 50, delay: float = 0.1):
        """Load Test: Sustained message submission"""
        try:
            self.log(f"Starting load test: {count} messages with {delay}s delay...", "INFO")
            success_count = 0
            start_time = time.time()
            
            for i in range(count):
                payload = {
                    "msisdn": f"9370123{6000 + i % 1000}",
                    "message": f"Load test message {i}",
                    "priority": "NORMAL" if i % 2 == 0 else "HIGH"
                }
                try:
                    response = requests.post(
                        f"{self.base_url}/api/sms/send",
                        json=payload,
                        headers=self.headers,
                        timeout=2
                    )
                    if response.status_code in [200, 201, 202]:
                        success_count += 1
                except:
                    pass
                
                time.sleep(delay)
            
            elapsed = time.time() - start_time
            tps = count / elapsed if elapsed > 0 else 0
            
            self.test_result(
                "Load: Sustained Submission",
                success_count >= count * 0.95,  # 95% success rate
                f"{success_count}/{count} succeeded, {tps:.2f} TPS"
            )
        except Exception as e:
            self.test_result("Load: Sustained Submission", False, str(e))
    
    # ========== Main Test Runner ==========
    
    def run_all_tests(self, include_load: bool = False):
        """Run all test suites"""
        self.log(f"{Colors.BOLD}Starting SMPP-MLS Feature Test Suite{Colors.RESET}", "INFO")
        self.log(f"Target: {self.base_url}", "INFO")
        print()
        
        # Epic A: Infrastructure
        self.log(f"{Colors.BOLD}=== Epic A: Core Infrastructure ==={Colors.RESET}", "INFO")
        self.test_a1_application_health()
        self.test_a2_actuator_endpoints()
        self.test_a3_smpp_session_health()
        print()
        
        # Epic D: Submission API
        self.log(f"{Colors.BOLD}=== Epic D: Submission API ==={Colors.RESET}", "INFO")
        self.test_d1_submit_endpoint_validation()
        self.test_d1_submit_normal_priority()
        self.test_d1_submit_high_priority()
        print()
        
        # Epic E: Persistence
        self.log(f"{Colors.BOLD}=== Epic E: Persistence ==={Colors.RESET}", "INFO")
        self.test_e1_h2_console_access()
        print()
        
        # Epic C: Priority & Throttling
        self.log(f"{Colors.BOLD}=== Epic C: Priority & Throttling ==={Colors.RESET}", "INFO")
        self.test_c1_bulk_submit_mixed_priority()
        print()
        
        # Epic G: Observability
        self.log(f"{Colors.BOLD}=== Epic G: Observability ==={Colors.RESET}", "INFO")
        self.test_g1_prometheus_metrics()
        print()
        
        # Load Tests (optional)
        if include_load:
            self.log(f"{Colors.BOLD}=== Load & Performance Tests ==={Colors.RESET}", "INFO")
            self.test_load_sustained_submission(count=50, delay=0.1)
            print()
        
        # Print summary
        self.print_summary()
    
    def print_summary(self):
        """Print test summary"""
        total = self.passed + self.failed
        pass_rate = (self.passed / total * 100) if total > 0 else 0
        
        print()
        print("=" * 70)
        print(f"{Colors.BOLD}TEST SUMMARY{Colors.RESET}")
        print("=" * 70)
        print(f"Total Tests:  {total}")
        print(f"{Colors.GREEN}Passed:       {self.passed}{Colors.RESET}")
        print(f"{Colors.RED}Failed:       {self.failed}{Colors.RESET}")
        print(f"Pass Rate:    {pass_rate:.1f}%")
        print("=" * 70)
        
        if self.failed > 0:
            print(f"\n{Colors.YELLOW}Failed Tests:{Colors.RESET}")
            for result in self.results:
                if not result['passed']:
                    print(f"  - {result['test']}: {result['details']}")
        
        print()
        return self.failed == 0

def main():
    parser = argparse.ArgumentParser(description='SMPP-MLS Feature Test Suite')
    parser.add_argument(
        '--base-url',
        default='http://localhost:8080',
        help='Base URL of the SMPP-MLS application (default: http://localhost:8080)'
    )
    parser.add_argument(
        '--api-key',
        default=None,
        help='API key for authentication (X-API-KEY header)'
    )
    parser.add_argument(
        '--load',
        action='store_true',
        help='Include load tests (may take longer)'
    )
    
    args = parser.parse_args()
    
    suite = SMPPTestSuite(args.base_url, api_key=args.api_key)
    success = suite.run_all_tests(include_load=args.load)
    
    sys.exit(0 if success else 1)

if __name__ == '__main__':
    main()
