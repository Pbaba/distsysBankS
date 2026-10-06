# Run Benchmark Suite against local servers and collect real measurements

$ports = @(8001, 8002, 8003)
Write-Host "Starting Bank Servers 1, 2, 3..."
$p1 = Start-Process -FilePath "java" -ArgumentList "-cp", "target/classes;target/test-classes", "com.distsys.bank.server.BankServer", "8001", "Server1" -PassThru
$p2 = Start-Process -FilePath "java" -ArgumentList "-cp", "target/classes;target/test-classes", "com.distsys.bank.server.BankServer", "8002", "Server2" -PassThru
$p3 = Start-Process -FilePath "java" -ArgumentList "-cp", "target/classes;target/test-classes", "com.distsys.bank.server.BankServer", "8003", "Server3" -PassThru

Start-Sleep -Seconds 2

Write-Host "Starting Bank Gateway (Port 8000)..."
$pgw = Start-Process -FilePath "java" -ArgumentList "-cp", "target/classes;target/test-classes", "com.distsys.bank.gateway.BankGateway", "8000" -PassThru

Start-Sleep -Seconds 2

Write-Host "Running BenchmarkRunner to collect real measurements..."
java -cp "target/classes;target/test-classes" com.distsys.bank.benchmark.BenchmarkRunner

Write-Host "Stopping servers..."
Stop-Process -Id $pgw.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p2.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p3.Id -Force -ErrorAction SilentlyContinue

Write-Host "Benchmark execution complete. Results saved to benchmark_results.csv and benchmark_results.json."
