@echo off
REM Starts BankServer 2 participant process on port 8002 (Partition A2xx).

cd /d "%~dp0.."
title BankServer 2 (Port 8002)
echo Starting Bank Server 2 (Port 8002, A2xx)...
mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8002 Server2"
