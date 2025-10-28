#!/usr/bin/env python3
"""
Afghan SMPP Simulator - Multi-Operator, Multi-Bind Support

Simulates real Afghan telecom operators:
- Afghan Telecom (AFTEL) - Port 2775
- Roshan - Port 2776
- Etisalat (AWCC) - Port 2777
- MTN Afghanistan - Port 2778
- Salaam - Port 2779

Each operator supports multiple concurrent binds and handles:
- bind_transmitter, bind_receiver, bind_transceiver
- submit_sm (message submission)
- deliver_sm (delivery receipts)
- enquire_link (keepalive)
- unbind

Usage:
    python afghan-smpp-simulator.py
    
    # Or run specific operators only
    python afghan-smpp-simulator.py --operators roshan mtn
"""

import socket
import struct
import threading
import time
import random
import argparse
from datetime import datetime
from typing import Dict, List, Set

# SMPP Command IDs
BIND_TRANSMITTER = 0x00000002
BIND_TRANSMITTER_RESP = 0x80000002
BIND_RECEIVER = 0x00000001
BIND_RECEIVER_RESP = 0x80000001
BIND_TRANSCEIVER = 0x00000009
BIND_TRANSCEIVER_RESP = 0x80000009
SUBMIT_SM = 0x00000004
SUBMIT_SM_RESP = 0x80000004
DELIVER_SM = 0x00000005
DELIVER_SM_RESP = 0x80000005
ENQUIRE_LINK = 0x00000015
ENQUIRE_LINK_RESP = 0x80000015
UNBIND = 0x00000006
UNBIND_RESP = 0x80000006

# SMPP Status Codes
ESME_ROK = 0x00000000
ESME_RINVPASWD = 0x00000001
ESME_RINVSYSID = 0x00000002
ESME_RTHROTTLED = 0x00000058

class Colors:
    """ANSI color codes"""
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    MAGENTA = '\033[95m'
    CYAN = '\033[96m'
    RESET = '\033[0m'
    BOLD = '\033[1m'

class OperatorConfig:
    """Configuration for each Afghan operator"""
    def __init__(self, name: str, port: int, system_id_prefix: str, 
                 prefixes: List[str], max_tps: int = 100, color: str = Colors.BLUE):
        self.name = name
        self.port = port
        self.system_id_prefix = system_id_prefix
        self.prefixes = prefixes
        self.max_tps = max_tps
        self.color = color
        self.active_sessions: Set[str] = set()
        self.message_counter = 0
        self.dlr_enabled = True
        
    def accepts_system_id(self, system_id: str) -> bool:
        """Check if system_id is valid for this operator"""
        # Accept any system_id starting with prefix or containing operator name
        return (system_id.lower().startswith(self.system_id_prefix.lower()) or 
                self.name.lower() in system_id.lower())
    
    def log(self, message: str, level: str = "INFO"):
        """Log with operator color"""
        timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        print(f"{self.color}[{timestamp}] [{self.name:12s}] [{level}] {message}{Colors.RESET}")

# Define Afghan Operators
OPERATORS = {
    'aftel': OperatorConfig(
        name='AFTEL',
        port=2775,
        system_id_prefix='aftel',
        prefixes=['93-20', '93-25'],
        max_tps=150,
        color=Colors.GREEN
    ),
    'roshan': OperatorConfig(
        name='Roshan',
        port=2776,
        system_id_prefix='roshan',
        prefixes=['93-79', '93-77', '93-72'],
        max_tps=200,
        color=Colors.BLUE
    ),
    'awcc': OperatorConfig(
        name='AWCC',
        port=2777,
        system_id_prefix='awcc',
        prefixes=['93-70', '93-71'],
        max_tps=150,
        color=Colors.YELLOW
    ),
    'mtn': OperatorConfig(
        name='MTN',
        port=2778,
        system_id_prefix='mtn',
        prefixes=['93-78', '93-76'],
        max_tps=180,
        color=Colors.MAGENTA
    ),
    'salaam': OperatorConfig(
        name='Salaam',
        port=2779,
        system_id_prefix='salaam',
        prefixes=['93-74', '93-75'],
        max_tps=100,
        color=Colors.CYAN
    )
}

class SMPPSession:
    """Handles individual SMPP session"""
    def __init__(self, client_socket, addr, operator: OperatorConfig):
        self.socket = client_socket
        self.addr = addr
        self.operator = operator
        self.bound = False
        self.system_id = None
        self.bind_type = None
        self.session_id = f"{operator.name}_{int(time.time() * 1000) % 100000}"
        self.message_ids = []
        
    def log(self, message: str, level: str = "INFO"):
        """Log with session context"""
        session_info = f"{self.system_id or 'unbound'}"
        self.operator.log(f"[{session_info}] {message}", level)
    
    def read_pdu(self):
        """Read a complete PDU from socket"""
        # Read header (16 bytes)
        header = b''
        while len(header) < 16:
            chunk = self.socket.recv(16 - len(header))
            if not chunk:
                return None, None, None, None
            header += chunk
        
        # Parse header
        command_length, command_id, command_status, sequence = struct.unpack('>IIII', header)
        
        # Read body
        body = b''
        if command_length > 16:
            remaining = command_length - 16
            while len(body) < remaining:
                chunk = self.socket.recv(remaining - len(body))
                if not chunk:
                    return None, None, None, None
                body += chunk
        
        return command_id, command_status, sequence, body
    
    def send_response(self, command_id: int, status: int, sequence: int, body: bytes = b''):
        """Send PDU response"""
        length = 16 + len(body)
        header = struct.pack('>IIII', length, command_id, status, sequence)
        self.socket.send(header + body)
    
    def parse_cstring(self, data: bytes, offset: int = 0) -> tuple:
        """Parse null-terminated string from bytes"""
        end = data.find(b'\x00', offset)
        if end == -1:
            return data[offset:].decode('latin-1'), len(data)
        return data[offset:end].decode('latin-1'), end + 1
    
    def handle_bind(self, command_id: int, sequence: int, body: bytes):
        """Handle bind request"""
        try:
            # Parse bind PDU
            system_id, offset = self.parse_cstring(body, 0)
            password, offset = self.parse_cstring(body, offset)
            system_type, offset = self.parse_cstring(body, offset)
            
            self.log(f"Bind request: system_id={system_id}, type={hex(command_id)}")
            
            # Validate credentials
            if not self.operator.accepts_system_id(system_id):
                self.log(f"Invalid system_id: {system_id}", "WARN")
                resp_cmd = command_id | 0x80000000
                self.send_response(resp_cmd, ESME_RINVSYSID, sequence)
                return False
            
            # Accept bind
            self.bound = True
            self.system_id = system_id
            self.bind_type = command_id
            self.operator.active_sessions.add(self.session_id)
            
            # Send response
            resp_cmd = command_id | 0x80000000
            resp_body = f"{self.operator.name}_SMSC\x00".encode('latin-1')
            self.send_response(resp_cmd, ESME_ROK, sequence, resp_body)
            
            self.log(f"Bound successfully (sessions: {len(self.operator.active_sessions)})", "INFO")
            return True
            
        except Exception as e:
            self.log(f"Bind error: {e}", "ERROR")
            return False
    
    def handle_submit_sm(self, sequence: int, body: bytes):
        """Handle submit_sm (message submission)"""
        try:
            # Parse submit_sm (simplified)
            service_type, offset = self.parse_cstring(body, 0)
            
            # Skip source addr TON/NPI (2 bytes)
            offset += 2
            source_addr, offset = self.parse_cstring(body, offset)
            
            # Skip dest addr TON/NPI (2 bytes)
            offset += 2
            dest_addr, offset = self.parse_cstring(body, offset)
            
            # Skip ESM class, protocol_id, priority (3 bytes)
            offset += 3
            
            # Skip schedule/validity (2 null-terminated strings)
            _, offset = self.parse_cstring(body, offset)
            _, offset = self.parse_cstring(body, offset)
            
            # Skip registered_delivery, replace_if_present, data_coding, sm_default_msg_id (4 bytes)
            offset += 4
            
            # Get message length and text
            if offset < len(body):
                sm_length = body[offset]
                offset += 1
                message = body[offset:offset + sm_length].decode('latin-1', errors='ignore')
            else:
                message = ""
            
            # Generate message ID
            self.operator.message_counter += 1
            msg_id = f"{self.operator.name}{self.operator.message_counter:08d}"
            self.message_ids.append(msg_id)
            
            self.log(f"Submit: dest={dest_addr}, msg='{message[:30]}...', id={msg_id}")
            
            # Send response
            resp_body = f"{msg_id}\x00".encode('latin-1')
            self.send_response(SUBMIT_SM_RESP, ESME_ROK, sequence, resp_body)
            
            # Schedule DLR if enabled
            if self.operator.dlr_enabled:
                threading.Thread(
                    target=self.send_dlr_after_delay,
                    args=(msg_id, dest_addr, message),
                    daemon=True
                ).start()
            
            return True
            
        except Exception as e:
            self.log(f"Submit error: {e}", "ERROR")
            self.send_response(SUBMIT_SM_RESP, 0x00000008, sequence)  # System error
            return False
    
    def send_dlr_after_delay(self, msg_id: str, dest_addr: str, original_msg: str):
        """Send delivery receipt after random delay (1-5 seconds)"""
        time.sleep(random.uniform(1.0, 5.0))
        
        if not self.bound:
            return
        
        try:
            # Build DLR text
            dlr_text = (
                f"id:{msg_id} sub:001 dlvrd:001 submit date:2501290000 "
                f"done date:2501290000 stat:DELIVRD err:000 text:{original_msg[:20]}"
            )
            
            # Build deliver_sm PDU (simplified)
            pdu_body = b''
            pdu_body += b'\x00'  # service_type
            pdu_body += struct.pack('BB', 1, 1)  # source TON/NPI
            pdu_body += dest_addr.encode('latin-1') + b'\x00'
            pdu_body += struct.pack('BB', 1, 1)  # dest TON/NPI
            pdu_body += b'\x00'  # destination (empty)
            pdu_body += struct.pack('BBB', 0x04, 0, 0)  # ESM class (DLR), protocol, priority
            pdu_body += b'\x00'  # schedule_delivery_time
            pdu_body += b'\x00'  # validity_period
            pdu_body += struct.pack('BBBB', 0, 0, 0, 0)  # registered_delivery, replace, data_coding, sm_default
            
            # Add message
            dlr_bytes = dlr_text.encode('latin-1')
            pdu_body += struct.pack('B', len(dlr_bytes))
            pdu_body += dlr_bytes
            
            # Send deliver_sm
            sequence = random.randint(1000, 9999)
            length = 16 + len(pdu_body)
            header = struct.pack('>IIII', length, DELIVER_SM, 0, sequence)
            self.socket.send(header + pdu_body)
            
            self.log(f"DLR sent: id={msg_id}, stat=DELIVRD")
            
            # Wait for deliver_sm_resp (optional, don't block)
            # Skip waiting for response to avoid blocking the main thread
            # self.socket.settimeout(0.5)
            # try:
            #     resp_header = self.socket.recv(16)
            #     if resp_header:
            #         self.log(f"DLR acknowledged", "DEBUG")
            # except socket.timeout:
            #     pass
            # finally:
            #     self.socket.settimeout(None)
                
        except Exception as e:
            self.log(f"DLR send error: {e}", "WARN")
    
    def handle_enquire_link(self, sequence: int):
        """Handle enquire_link (keepalive)"""
        try:
            self.send_response(ENQUIRE_LINK_RESP, ESME_ROK, sequence)
            # self.log("Enquire link", "DEBUG")  # Reduce log spam
        except Exception as e:
            self.log(f"Enquire link error: {e}", "ERROR")
    
    def handle_unbind(self, sequence: int):
        """Handle unbind request"""
        self.send_response(UNBIND_RESP, ESME_ROK, sequence)
        self.log("Unbind received")
        return False  # Signal to close connection
    
    def run(self):
        """Main session loop"""
        try:
            while True:
                command_id, command_status, sequence, body = self.read_pdu()
                
                if command_id is None:
                    break
                
                # Handle different PDU types
                if command_id in [BIND_TRANSMITTER, BIND_RECEIVER, BIND_TRANSCEIVER]:
                    if not self.handle_bind(command_id, sequence, body):
                        break
                
                elif command_id == SUBMIT_SM:
                    if not self.bound:
                        self.send_response(SUBMIT_SM_RESP, 0x00000005, sequence)  # Bind error
                    else:
                        self.handle_submit_sm(sequence, body)
                
                elif command_id == ENQUIRE_LINK:
                    self.handle_enquire_link(sequence)
                
                elif command_id == UNBIND:
                    if not self.handle_unbind(sequence):
                        break
                
                elif command_id == DELIVER_SM_RESP:
                    # Client acknowledged our DLR
                    pass
                
                else:
                    self.log(f"Unknown command: {hex(command_id)}", "WARN")
        
        except Exception as e:
            self.log(f"Session error: {e}", "ERROR")
        
        finally:
            self.cleanup()
    
    def cleanup(self):
        """Clean up session"""
        if self.session_id in self.operator.active_sessions:
            self.operator.active_sessions.remove(self.session_id)
        
        try:
            self.socket.close()
        except:
            pass
        
        self.log(f"Session closed (active: {len(self.operator.active_sessions)})")

def run_operator_server(operator: OperatorConfig):
    """Run SMPP server for one operator"""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', operator.port))
    server.listen(10)
    
    operator.log(f"SMPP Server started on port {operator.port} (prefixes: {', '.join(operator.prefixes)})")
    
    try:
        while True:
            client, addr = server.accept()
            session = SMPPSession(client, addr, operator)
            thread = threading.Thread(target=session.run, daemon=True)
            thread.start()
    except KeyboardInterrupt:
        pass
    finally:
        server.close()
        operator.log("Server stopped")

def main():
    import signal
    import sys
    
    # Handle Ctrl+C gracefully on Windows
    def signal_handler(sig, frame):
        print(f"\n{Colors.YELLOW}Shutting down...{Colors.RESET}")
        sys.exit(0)
    
    signal.signal(signal.SIGINT, signal_handler)
    
    parser = argparse.ArgumentParser(description='Afghan SMPP Simulator - Multi-Operator')
    parser.add_argument(
        '--operators',
        nargs='+',
        choices=list(OPERATORS.keys()) + ['all'],
        default=['all'],
        help='Operators to simulate (default: all)'
    )
    parser.add_argument(
        '--no-dlr',
        action='store_true',
        help='Disable automatic delivery receipts'
    )
    
    args = parser.parse_args()
    
    # Determine which operators to run
    if 'all' in args.operators:
        operators_to_run = list(OPERATORS.values())
    else:
        operators_to_run = [OPERATORS[op] for op in args.operators]
    
    # Disable DLR if requested
    if args.no_dlr:
        for op in operators_to_run:
            op.dlr_enabled = False
    
    print(f"{Colors.BOLD}{'='*70}{Colors.RESET}")
    print(f"{Colors.BOLD}Afghan SMPP Simulator - Multi-Operator{Colors.RESET}")
    print(f"{Colors.BOLD}{'='*70}{Colors.RESET}")
    print()
    print("Starting operators:")
    for op in operators_to_run:
        print(f"  {op.color}• {op.name:12s} - Port {op.port} - Prefixes: {', '.join(op.prefixes)}{Colors.RESET}")
    print()
    print(f"Press Ctrl+C to stop (or close the window)")
    print(f"{Colors.BOLD}{'='*70}{Colors.RESET}")
    print()
    
    # Start server threads
    threads = []
    for operator in operators_to_run:
        thread = threading.Thread(target=run_operator_server, args=(operator,), daemon=True)
        thread.start()
        threads.append(thread)
    
    # Wait for interrupt
    try:
        while True:
            time.sleep(1)
    except (KeyboardInterrupt, SystemExit):
        print(f"\n{Colors.YELLOW}Shutting down...{Colors.RESET}")
        sys.exit(0)

if __name__ == '__main__':
    main()
