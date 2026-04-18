#!/bin/bash

# Start TCP forwarder on 9999 forwarding to 10000 with length-prefixed protocol enabled
java -jar target/tcp-forward.jar --local-port 9999 --remote-host 127.0.0.1 --remote-port 10000 --protocol length-prefixed --prefix-size 2 --prefix-endianness big > forwarder_logs_lp.txt 2>&1 &
FWD_PID=$!

# Run python script
python3 test_length_prefixed.py > python_output.txt 2>&1

# Stop the forwarder
kill -INT $FWD_PID
wait $FWD_PID 2>/dev/null

echo "--- Python Script Output ---"
cat python_output.txt

echo -e "\n--- Forwarder Logs ---"
cat forwarder_logs_lp.txt
