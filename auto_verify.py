#!/usr/bin/env python3
"""
Automated Verification Script - Calls REST API to verify database state

Usage:
    python auto_verify.py
"""

import requests
import json
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

def print_table(data, title=""):
    if title:
        print(f"{Colors.BOLD}{title}{Colors.RESET}")
    
    if not data:
        print(f"{Colors.YELLOW}No data{Colors.RESET}\n")
        return
    
    # Get column names
    if isinstance(data, list) and len(data) > 0:
        columns = list(data[0].keys())
        
        # Print header
        header = " | ".join(f"{col:15s}" for col in columns)
        print(header)
        print("-" * len(header))
        
        # Print rows
        for row in data:
            row_str = " | ".join(f"{str(row.get(col, 'NULL')):15s}" for col in columns)
            print(row_str)
        
        print(f"\n{Colors.GREEN}✓ {len(data)} rows{Colors.RESET}\n")
    else:
        print(json.dumps(data, indent=2))
        print()

def verify_all(base_url="http://localhost:8080"):
    print(f"\n{Colors.BOLD}{'='*80}{Colors.RESET}")
    print(f"{Colors.BOLD}SMPP-MLS Automated Verification{Colors.RESET}")
    print(f"{Colors.BOLD}Timestamp: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}{Colors.RESET}")
    print(f"{Colors.BOLD}{'='*80}{Colors.RESET}\n")
    
    try:
        # Get full verification
        response = requests.get(f"{base_url}/api/verify/all", timeout=10)
        
        if response.status_code != 200:
            print(f"{Colors.RED}✗ Failed to get verification data: HTTP {response.status_code}{Colors.RESET}")
            return False
        
        data = response.json()
        
        # 1. Status Summary
        print_section("1. MESSAGE STATUS SUMMARY")
        status_data = data.get('status', {})
        print_table(status_data.get('statusBreakdown', []))
        
        total_queued = status_data.get('totalQueued', 0)
        total_sent = status_data.get('totalSent', 0)
        warning = status_data.get('warning')
        
        print(f"Total Queued: {Colors.YELLOW}{total_queued}{Colors.RESET}")
        print(f"Total Sent:   {Colors.GREEN}{total_sent}{Colors.RESET}")
        
        if warning:
            print(f"\n{Colors.RED}⚠ WARNING: {warning}{Colors.RESET}")
        elif total_sent > 0:
            print(f"\n{Colors.GREEN}✓ Messages are being processed successfully{Colors.RESET}")
        
        # 2. Session Distribution
        print_section("2. SESSION DISTRIBUTION")
        session_data = data.get('sessions', {})
        print_table(session_data.get('sessionDistribution', []))
        
        # 3. Priority Analysis
        print_section("3. PRIORITY ANALYSIS")
        priority_data = data.get('priority', {})
        
        if 'error' in priority_data:
            print(f"{Colors.YELLOW}{priority_data['error']}{Colors.RESET}\n")
        else:
            print_table(priority_data.get('priorityPercentages', []), "HP vs NP Percentage:")
            
            hp_pct = priority_data.get('hpPercentage')
            hp_ok = priority_data.get('hpThrottlingOk')
            hp_warning = priority_data.get('hpWarning')
            
            if hp_pct is not None:
                if hp_ok:
                    print(f"{Colors.GREEN}✓ HP Throttling OK: {hp_pct}% (expected ≤20-25%){Colors.RESET}\n")
                else:
                    print(f"{Colors.RED}✗ {hp_warning}{Colors.RESET}\n")
            
            # Show first sent messages
            first_sent = priority_data.get('firstSentMessages', [])
            if first_sent:
                print_table(first_sent[:10], "First 10 Sent Messages (HP should appear first):")
                
                # Analyze if HP was sent first
                hp_count = sum(1 for msg in first_sent[:5] if msg.get('PRIORITY') == 'HIGH')
                if hp_count >= 3:
                    print(f"{Colors.GREEN}✓ HP messages appear to be prioritized (found {hp_count}/5 in first 5){Colors.RESET}\n")
                else:
                    print(f"{Colors.YELLOW}⚠ Only {hp_count}/5 HP messages in first 5 sent{Colors.RESET}\n")
        
        # 4. Routing Analysis
        print_section("4. OPERATOR ROUTING")
        routing_data = data.get('routing', {})
        print_table(routing_data.get('operatorRouting', []))
        
        # 5. Retries
        print_section("5. RETRY ANALYSIS")
        retries = data.get('retries')
        if isinstance(retries, str):
            print(f"{Colors.GREEN}✓ {retries}{Colors.RESET}\n")
        else:
            print_table(retries)
        
        # Final Summary
        print_section("VERIFICATION SUMMARY")
        
        checks = []
        checks.append(("Messages being sent", total_sent > 0))
        checks.append(("No stuck messages", not (total_queued > 0 and total_sent == 0)))
        
        if hp_pct is not None:
            checks.append(("HP throttling working", hp_ok))
        
        all_passed = all(result for _, result in checks)
        
        for check_name, result in checks:
            status = f"{Colors.GREEN}✓ PASS{Colors.RESET}" if result else f"{Colors.RED}✗ FAIL{Colors.RESET}"
            print(f"  {status}  {check_name}")
        
        print()
        if all_passed:
            print(f"{Colors.GREEN}{Colors.BOLD}✓ ALL CHECKS PASSED{Colors.RESET}\n")
        else:
            print(f"{Colors.YELLOW}{Colors.BOLD}⚠ SOME CHECKS FAILED - Review warnings above{Colors.RESET}\n")
        
        return all_passed
        
    except requests.exceptions.ConnectionError:
        print(f"{Colors.RED}✗ Cannot connect to {base_url}{Colors.RESET}")
        print(f"{Colors.YELLOW}Make sure your Spring Boot application is running{Colors.RESET}\n")
        return False
    except Exception as e:
        print(f"{Colors.RED}✗ Error: {e}{Colors.RESET}\n")
        return False

if __name__ == '__main__':
    import sys
    success = verify_all()
    sys.exit(0 if success else 1)
