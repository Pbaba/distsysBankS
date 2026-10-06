#!/usr/bin/env bash
# Start Bank Servers (8001, 8002, 8003) and Bank Gateway (8000)

echo "Starting Bank Server 1 (Port 8001, A1xx)..."
mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8001 Server1" > server1.log 2>&1 &
PID_S1=$!

echo "Starting Bank Server 2 (Port 8002, A2xx)..."
mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8002 Server2" > server2.log 2>&1 &
PID_S2=$!

echo "Starting Bank Server 3 (Port 8003, A3xx)..."
mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8003 Server3" > server3.log 2>&1 &
PID_S3=$!

sleep 3

echo "Starting Bank Gateway (Port 8000)..."
mvn exec:java -Dexec.mainClass=com.distsys.bank.gateway.BankGateway > gateway.log 2>&1 &
PID_GW=$!

echo "Processes started: S1=$PID_S1, S2=$PID_S2, S3=$PID_S3, Gateway=$PID_GW"
echo "Press Ctrl+C or kill PIDs to stop."
