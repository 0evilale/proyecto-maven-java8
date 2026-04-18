#!/bin/bash

# Start a simple netcat listener on port 10000 in the background
nc -l -p 10000 > received_data.txt &
NC_PID=$!

# Wait a second to ensure netcat is listening
sleep 1

# Start the TCP forwarder listening on 9999, forwarding to 10000
java -jar target/tcp-forward.jar --local-port 9999 --remote-host 127.0.0.1 --remote-port 10000 > forwarder_logs.txt 2>&1 &
FWD_PID=$!

# Wait a second to ensure the forwarder is listening
sleep 1

# Send a test message to the forwarder on port 9999
echo "Hello from the test client!" | nc 127.0.0.1 9999

# Wait for the data to be processed and written
sleep 2

# Stop the forwarder
kill -INT $FWD_PID

# Netcat listener should have stopped after receiving the message and client disconnecting,
# but we can kill it just in case
kill $NC_PID 2>/dev/null

echo "--- Received Data at Target Server ---"
cat received_data.txt

echo -e "\n--- Forwarder Logs ---"
cat forwarder_logs.txt
