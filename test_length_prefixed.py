import socket
import struct
import time
import threading
import sys

def server():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('127.0.0.1', 10000))
    s.listen(1)
    conn, addr = s.accept()
    # Read length prefix
    prefix = conn.recv(2)
    length = struct.unpack('>H', prefix)[0]
    payload = conn.recv(length)
    print(f"SERVER received payload of {length} bytes: {payload}")
    # reply back
    reply_payload = b"Acknowledged"
    reply_prefix = struct.pack('>H', len(reply_payload))
    conn.send(reply_prefix + reply_payload)
    conn.close()
    s.close()

def client():
    time.sleep(1) # wait for server and forwarder to start
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect(('127.0.0.1', 9999))
    payload = b'Hello Length-Prefixed Protocol!'
    # Send fragmented prefix
    length = len(payload)
    prefix = struct.pack('>H', length)
    s.send(prefix[0:1])
    time.sleep(0.5)
    s.send(prefix[1:2])
    # Send fragmented payload
    time.sleep(0.5)
    s.send(payload[0:10])
    time.sleep(0.5)
    s.send(payload[10:])
    print("CLIENT finished sending.")

    # Read reply
    reply_prefix = s.recv(2)
    reply_length = struct.unpack('>H', reply_prefix)[0]
    reply_payload = s.recv(reply_length)
    print(f"CLIENT received reply of {reply_length} bytes: {reply_payload}")
    s.close()

t_server = threading.Thread(target=server)
t_server.start()

t_client = threading.Thread(target=client)
t_client.start()

t_server.join()
t_client.join()
