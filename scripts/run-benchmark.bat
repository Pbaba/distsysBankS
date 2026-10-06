@echo off
REM Run benchmark harness against Gateway (Port 8000)

mvn exec:java -Dexec.mainClass=com.distsys.bank.benchmark.BenchmarkRunner
