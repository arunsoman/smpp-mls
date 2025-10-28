#!/usr/bin/env python3
"""
Simple SMPP Server Mock for Testing
Listens on port 2775 and accepts basic SMPP bind requests
Handles enquire_link (keepalive) to maintain connection
"""

import socket
import struct
import threading
import time

def handle_client(client_socket, addr):
    print(f"Connection from {addr}")
    try:
        while True:
            # Read PDU header (16 bytes)
            header = b''
            while len(header) < 16:
                chunk = client_socket.recv(16 - len(header))
                if not chunk:
                    return
                header += chunk
            
            # Parse PDU header
            command_length, command_id, command_status, sequence = struct.unpack('>IIII', header)
            print(f"Received PDU: length={command_length}, cmd_id={hex(command_id)}, seq={sequence}")
            
            # Read remaining PDU body if any
            body = b''
            if command_length > 16:
                remaining = command_length - 16
                while len(body) < remaining:
                    chunk = client_socket.recv(remaining - len(body))
                    if not chunk:
                        return
                    body += chunk
            
            # Handle bind_transmitter (0x00000002)
            if command_id == 0x00000002:
                print("Received BIND_TRANSMITTER request")
                # Send bind_transmitter_resp (0x80000002)
                resp_cmd_id = 0x80000002
                resp_status = 0  # ESME_ROK
                system_id = b'SMPPSim\x00'
                resp_length = 16 + len(system_id)
                response = struct.pack('>IIII', resp_length, resp_cmd_id, resp_status, sequence) + system_id
                client_socket.send(response)
                print("Sent BIND_TRANSMITTER_RESP")
            
            # Handle bind_transceiver (0x00000009)
            elif command_id == 0x00000009:
                print("Received BIND_TRANSCEIVER request")
                # Send bind_transceiver_resp (0x80000009)
                resp_cmd_id = 0x80000009
                resp_status = 0  # ESME_ROK
                system_id = b'SMPPSim\x00'
                resp_length = 16 + len(system_id)
                response = struct.pack('>IIII', resp_length, resp_cmd_id, resp_status, sequence) + system_id
                client_socket.send(response)
                print("Sent BIND_TRANSCEIVER_RESP")
            
            # Handle enquire_link (0x00000015)
            elif command_id == 0x00000015:
                print("Received ENQUIRE_LINK")
                # Send enquire_link_resp (0x80000015)
                resp_cmd_id = 0x80000015
                resp_status = 0
                resp_length = 16
                response = struct.pack('>IIII', resp_length, resp_cmd_id, resp_status, sequence)
                client_socket.send(response)
                print("Sent ENQUIRE_LINK_RESP")
            
            # Handle submit_sm (0x00000004)
            elif command_id == 0x00000004:
                print("Received SUBMIT_SM")
                # Send submit_sm_resp (0x80000004)
                resp_cmd_id = 0x80000004
                resp_status = 0
                message_id = b'msg123\x00'
                resp_length = 16 + len(message_id)
                response = struct.pack('>IIII', resp_length, resp_cmd_id, resp_status, sequence) + message_id
                client_socket.send(response)
                print("Sent SUBMIT_SM_RESP with message_id=msg123")
            
            # Handle unbind (0x00000006)
            elif command_id == 0x00000006:
                print("Received UNBIND")
                # Send unbind_resp (0x80000006)
                resp_cmd_id = 0x80000006
                resp_status = 0
                resp_length = 16
                response = struct.pack('>IIII', resp_length, resp_cmd_id, resp_status, sequence)
                client_socket.send(response)
                print("Sent UNBIND_RESP")
                break
                
    except Exception as e:
        print(f"Error handling client: {e}")
    finally:
        client_socket.close()
        print(f"Connection closed from {addr}")

def main():
    host = '0.0.0.0'
    port = 2775
    
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((host, port))
    server.listen(5)
    
    print(f"Simple SMPP Server listening on {host}:{port}")
    print("Press Ctrl+C to stop")
    
    try:
        while True:
            client, addr = server.accept()
            thread = threading.Thread(target=handle_client, args=(client, addr))
            thread.start()
    except KeyboardInterrupt:
        print("\nShutting down server...")
    finally:
        server.close()

if __name__ == '__main__':
    main()
