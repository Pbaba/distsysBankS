@echo off
REM Starts BankGateway coordinator process on port 8000.

cd /d "%~dp0.."
title Bank Gateway (Port 8000)
echo Starting Bank Gateway (Port 8000)...
mvn exec:java -Dexec.mainClass=com.distsys.bank.gateway.BankGateway
