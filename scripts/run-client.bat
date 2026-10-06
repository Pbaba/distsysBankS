@echo off
REM Run interactive BankClient connected to Gateway (Port 8000)

mvn exec:java -Dexec.mainClass=com.distsys.bank.client.BankClient
