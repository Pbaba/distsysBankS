@echo off
REM Start Bank Servers (8001, 8002, 8003) and Bank Gateway (8000)

echo Starting Bank Server 1 (Port 8001, A1xx)...
start "BankServer 1 (Port 8001)" cmd /k "mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8001 Server1""

echo Starting Bank Server 2 (Port 8002, A2xx)...
start "BankServer 2 (Port 8002)" cmd /k "mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8002 Server2""

echo Starting Bank Server 3 (Port 8003, A3xx)...
start "BankServer 3 (Port 8003)" cmd /k "mvn exec:java -Dexec.mainClass=com.distsys.bank.server.BankServer -Dexec.args="8003 Server3""

echo Waiting 3 seconds for bank servers to bind...
timeout /t 3 /nobreak >nul

echo Starting Bank Gateway (Port 8000)...
start "Bank Gateway (Port 8000)" cmd /k "mvn exec:java -Dexec.mainClass=com.distsys.bank.gateway.BankGateway"

echo All servers launched in separate windows!
