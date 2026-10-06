@echo off
REM Starts BankServer 3 participant process on port 8003 (Partition A3xx).

cd /d "%~dp0.."
title BankServer 3 (Port 8003)
echo Starting Bank Server 3 (Port 8003, A3xx)...
mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8003 Server3"
