#!/usr/bin/env python3
"""
Database Verification Script for SMPP-MLS

Connects to H2 database and runs verification queries to check:
- Message routing
- Priority handling
- TPS distribution
- Session distribution

Requires: pip install jaydebeapi JPype1
Download H2 driver: https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar

Usage:
    python verify_database.py
"""

import sys
import os

try:
    import jaydebeapi
    import jpype
except ImportError:
    print("ERROR: Required libraries not installed")
    print("Please run: pip install jaydebeapi JPype1")
    print("Also download H2 driver jar file")
    sys.exit(1)

class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    RESET = '\033[0m'
    BOLD = '\033[1m'

class DatabaseVerifier:
    def __init__(self, jdbc_url="jdbc:h2:tcp://localhost:9092/mem:smppdb", 
                 username="sa", password="password", 
                 h2_jar_path="h2-2.2.224.jar"):
        self.jdbc_url = jdbc_url
        self.username = username
        self.password = password
        self.h2_jar_path = h2_jar_path
        self.conn = None
        
    def connect(self):
        """Connect to H2 database"""
        try:
            if not os.path.exists(self.h2_jar_path):
                print(f"{Colors.RED}ERROR: H2 jar not found at {self.h2_jar_path}{Colors.RESET}")
                print(f"{Colors.YELLOW}Download from: https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar{Colors.RESET}")
                return False
            
            self.conn = jaydebeapi.connect(
                "org.h2.Driver",
                self.jdbc_url,
                [self.username, self.password],
                self.h2_jar_path
            )
            print(f"{Colors.GREEN}✓ Connected to database{Colors.RESET}\n")
            return True
        except Exception as e:
            print(f"{Colors.RED}✗ Failed to connect: {e}{Colors.RESET}")
            print(f"{Colors.YELLOW}Make sure your Spring Boot app is running with H2 TCP server enabled{Colors.RESET}")
            return False
    
    def execute_query(self, query, description):
        """Execute a query and display results"""
        try:
            cursor = self.conn.cursor()
            cursor.execute(query)
            
            # Get column names
            columns = [desc[0] for desc in cursor.description]
            
            # Fetch all rows
            rows = cursor.fetchall()
            
            print(f"{Colors.BOLD}{description}{Colors.RESET}")
            print(f"{Colors.CYAN}{'='*80}{Colors.RESET}")
            
            if not rows:
                print(f"{Colors.YELLOW}No data found{Colors.RESET}\n")
                return []
            
            # Print header
            header = " | ".join(f"{col:15s}" for col in columns)
            print(header)
            print("-" * len(header))
            
            # Print rows
            for row in rows:
                row_str = " | ".join(f"{str(val):15s}" if val is not None else f"{'NULL':15s}" for val in row)
                print(row_str)
            
            print(f"\n{Colors.GREEN}✓ {len(rows)} rows returned{Colors.RESET}\n")
            cursor.close()
            return rows
            
        except Exception as e:
            print(f"{Colors.RED}✗ Query failed: {e}{Colors.RESET}\n")
            return []
    
    def verify_all(self):
        """Run all verification queries"""
        print(f"{Colors.BOLD}{'='*80}{Colors.RESET}")
        print(f"{Colors.BOLD}SMPP-MLS Database Verification{Colors.RESET}")
        print(f"{Colors.BOLD}{'='*80}{Colors.RESET}\n")
        
        # 1. Overall status summary
        results = self.execute_query(
            """
            SELECT priority, status, COUNT(*) as count
            FROM sms_outbound
            GROUP BY priority, status
            ORDER BY priority DESC, status
            """,
            "1. Message Status Summary"
        )
        
        # Analyze results
        total_queued = sum(row[2] for row in results if row[1] == 'QUEUED')
        total_sent = sum(row[2] for row in results if row[1] == 'SENT')
        
        if total_queued > 0 and total_sent == 0:
            print(f"{Colors.RED}⚠ WARNING: {total_queued} messages queued but none sent!{Colors.RESET}")
            print(f"{Colors.YELLOW}  Check if SessionSender is running properly{Colors.RESET}\n")
        elif total_sent > 0:
            print(f"{Colors.GREEN}✓ Messages are being processed ({total_sent} sent){Colors.RESET}\n")
        
        # 2. Session distribution
        self.execute_query(
            """
            SELECT session_id, priority, COUNT(*) as count
            FROM sms_outbound
            WHERE session_id IS NOT NULL
            GROUP BY session_id, priority
            ORDER BY session_id, priority DESC
            """,
            "2. Message Distribution Across Sessions"
        )
        
        # 3. Sent messages timeline
        self.execute_query(
            """
            SELECT id, msisdn, LEFT(message, 20) as msg_preview, priority, status, created_at
            FROM sms_outbound
            WHERE status IN ('SENT', 'DELIVERED')
            ORDER BY created_at ASC
            LIMIT 20
            """,
            "3. First 20 Sent Messages (Check if HP sent first)"
        )
        
        # 4. HP vs NP percentage
        results = self.execute_query(
            """
            SELECT 
                priority,
                COUNT(*) as count,
                ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM sms_outbound WHERE status = 'SENT'), 2) as percentage
            FROM sms_outbound
            WHERE status = 'SENT'
            GROUP BY priority
            """,
            "4. HP vs NP Percentage (HP should be ~20% or less)"
        )
        
        # Analyze HP percentage
        for row in results:
            if row[0] == 'HIGH' and row[2] is not None:
                hp_pct = float(row[2])
                if hp_pct > 25:
                    print(f"{Colors.YELLOW}⚠ WARNING: HP percentage is {hp_pct}% (expected ≤20%){Colors.RESET}\n")
                else:
                    print(f"{Colors.GREEN}✓ HP throttling working correctly ({hp_pct}%){Colors.RESET}\n")
        
        # 5. Operator routing verification
        self.execute_query(
            """
            SELECT 
                CASE 
                    WHEN msisdn LIKE '9320%' OR msisdn LIKE '9325%' THEN 'AFTEL'
                    WHEN msisdn LIKE '9379%' OR msisdn LIKE '9377%' OR msisdn LIKE '9372%' THEN 'Roshan'
                    WHEN msisdn LIKE '9370%' OR msisdn LIKE '9371%' THEN 'AWCC'
                    WHEN msisdn LIKE '9378%' OR msisdn LIKE '9376%' THEN 'MTN'
                    WHEN msisdn LIKE '9374%' OR msisdn LIKE '9375%' THEN 'Salaam'
                    ELSE 'Unknown'
                END as operator,
                COUNT(*) as total,
                SUM(CASE WHEN status = 'SENT' THEN 1 ELSE 0 END) as sent
            FROM sms_outbound
            GROUP BY 
                CASE 
                    WHEN msisdn LIKE '9320%' OR msisdn LIKE '9325%' THEN 'AFTEL'
                    WHEN msisdn LIKE '9379%' OR msisdn LIKE '9377%' OR msisdn LIKE '9372%' THEN 'Roshan'
                    WHEN msisdn LIKE '9370%' OR msisdn LIKE '9371%' THEN 'AWCC'
                    WHEN msisdn LIKE '9378%' OR msisdn LIKE '9376%' THEN 'MTN'
                    WHEN msisdn LIKE '9374%' OR msisdn LIKE '9375%' THEN 'Salaam'
                    ELSE 'Unknown'
                END
            ORDER BY total DESC
            """,
            "5. Routing by Operator (Prefix-based)"
        )
        
        # 6. Retry analysis
        results = self.execute_query(
            """
            SELECT retry_count, COUNT(*) as count
            FROM sms_outbound
            WHERE retry_count > 0
            GROUP BY retry_count
            ORDER BY retry_count
            """,
            "6. Retry Count Analysis"
        )
        
        if not results:
            print(f"{Colors.GREEN}✓ No retries needed - all messages sent successfully{Colors.RESET}\n")
        
        # 7. DLR status
        results = self.execute_query(
            """
            SELECT 
                d.dlr_status,
                COUNT(*) as count
            FROM sms_dlr d
            GROUP BY d.dlr_status
            """,
            "7. Delivery Receipt Status"
        )
        
        if not results:
            print(f"{Colors.YELLOW}⚠ No DLRs received yet{Colors.RESET}\n")
        
        # Summary
        print(f"{Colors.BOLD}{'='*80}{Colors.RESET}")
        print(f"{Colors.BOLD}VERIFICATION COMPLETE{Colors.RESET}")
        print(f"{Colors.BOLD}{'='*80}{Colors.RESET}\n")
    
    def close(self):
        """Close database connection"""
        if self.conn:
            self.conn.close()
            print(f"{Colors.GREEN}✓ Database connection closed{Colors.RESET}")

def main():
    # Try to connect via REST API first (simpler approach)
    print(f"{Colors.YELLOW}Note: Direct H2 database connection requires jaydebeapi and H2 jar{Colors.RESET}")
    print(f"{Colors.YELLOW}Alternative: Use H2 Console at http://localhost:8080/h2-console{Colors.RESET}\n")
    
    verifier = DatabaseVerifier()
    
    if verifier.connect():
        try:
            verifier.verify_all()
        finally:
            verifier.close()
    else:
        print(f"\n{Colors.CYAN}Alternative: Run queries manually in H2 Console:{Colors.RESET}")
        print(f"1. Open: http://localhost:8080/h2-console")
        print(f"2. JDBC URL: jdbc:h2:mem:smppdb")
        print(f"3. Username: sa")
        print(f"4. Password: password")
        print(f"5. Run queries from verify_priority.sql\n")

if __name__ == '__main__':
    main()
