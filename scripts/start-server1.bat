@echo off
REM Starts BankServer 1 participant process on port 8001 (Partition A1xx).

cd /d "%~dp0.."
title BankServer 1 (Port 8001)
echo Starting Bank Server 1 (Port 8001, A1xx)...
mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8001 Server1"
