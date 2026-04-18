#!/bin/bash

# Start python http server on 10000 in a specific directory
mkdir -p mock_server
echo "Hello from Python HTTP server!" > mock_server/index.html
cd mock_server
python3 -m http.server 10000 > ../server_logs.txt 2>&1 &
SERVER_PID=$!
cd ..

sleep 1

# Start TCP forwarder on 9999 forwarding to 10000
java -jar target/tcp-forward.jar --local-port 9999 --remote-host 127.0.0.1 --remote-port 10000 > forwarder_logs.txt 2>&1 &
FWD_PID=$!

sleep 1

# Test with curl, retrieving the index.html from python server via forwarder
curl -s http://127.0.0.1:9999/ > result.txt

# Stop the services
kill -INT $FWD_PID
kill $SERVER_PID

echo "--- Curl Result ---"
cat result.txt

echo -e "\n--- Forwarder Logs ---"
cat forwarder_logs.txt
