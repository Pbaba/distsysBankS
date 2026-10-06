# Distributed Bank Transaction System

An academic, resilient distributed banking system in **Java 21** implementing distributed accounts, concurrent transactions, two-phase commit (2PC) money transfer across multiple bank servers, append-only transaction logging, failure simulation, and performance benchmarking.

---

## 1. System Architecture

The system consists of 5 standalone distributed components communicating via standard **Java TCP Sockets** with a newline-delimited UTF-8 text protocol:

```
+-----------------------------------------------------------------------+
|                             CLIENT TIER                               |
|   +---------------------------------------------------------------+   |
|   |                         BankClient                            |   |
|   |         (CLI / Automated Tests / Benchmark Runner)            |   |
|   +---------------------------------------------------------------+   |
+-----------------------------------|-----------------------------------+
                                    | TCP (Port 8000)
                                    v
+-----------------------------------------------------------------------+
|                          COORDINATOR TIER                             |
|   +---------------------------------------------------------------+   |
|   |                         BankGateway                           |   |
|   |  - Thread Pool (ExecutorService)                              |   |
|   |  - Routing Table (A1xx -> S1, A2xx -> S2, A3xx -> S3)         |   |
|   |  - 2PC Transaction Coordinator (Durable fsync Decision Log)   |   |
|   |  - Append-Only Transaction Logger (transactions.log)          |   |
|   +---------------------------------------------------------------+   |
+-----------|-----------------------|-----------------------|-----------+
            | TCP (8001)            | TCP (8002)            | TCP (8003)
            v                       v                       v
+-----------------------+ +-----------------------+ +-------------------+
|     BankServer 1      | |     BankServer 2      | |   BankServer 3    |
|   (Port 8001, A1xx)   | |   (Port 8002, A2xx)   | | (Port 8003, A3xx) |
|                       | |                       | |                   |
| - Accounts:           | | - Accounts:           | | - Accounts:       |
|   A101 (Ram: ₹10,000) | |   A201 (Lakshman: ₹20k| |   A301 (Bharat)   |
|   A102 (Sita: ₹10,000)| |   A202 (Urmila)       | |   A302 (Mandavi)  |
| - In-Memory State     | | - In-Memory State     | | - In-Memory State |
| - ReentrantLocks      | | - ReentrantLocks      | | - ReentrantLocks  |
| - 2PC Participant     | | - 2PC Participant     | | - 2PC Participant |
| - Durable Intent Log  | | - Durable Intent Log  | | - Durable Intent  |
| - Failure Hooks       | | - Failure Hooks       | | - Failure Hooks   |
+-----------------------+ +-----------------------+ +-------------------+
```

### 1.1 Port & Account Partitioning Scheme

Accounts are partitioned deterministically across servers by ID prefix:
- **Bank Gateway:** `localhost:8000` (Coordinator & Client proxy)
- **Bank Server 1:** `localhost:8001` (Partition `A1xx`: e.g. `A101`, `A102`)
- **Bank Server 2:** `localhost:8002` (Partition `A2xx`: e.g. `A201`, `A202`)
- **Bank Server 3:** `localhost:8003` (Partition `A3xx`: e.g. `A301`, `A302`)
- Unmapped accounts (e.g. `A999`) fail immediately with `Destination account not found`.

---

## 2. Key Distributed Systems Features

### 2.1 Currency Representation
All balances and amounts are stored and computed strictly as **`long` integer paise** ($1\text{ Rupee} = 100\text{ paise}$). Floating-point types (`double`/`float`) are prohibited to eliminate representation errors.

### 2.2 Two-Phase Commit (2PC) with Strict Invariants
Cross-server transfers (`TRANSFER A101 A201 5000`) adhere to strict coordinator-participant state transitions:

1. **Before Global Decision:**
   - Gateway sends `PREPARE_DEBIT` to Server 1 (source reserves funds via `reservedDebits += amount`) and `PREPARE_CREDIT` to Server 2.
   - If any participant votes `VOTE_ABORT`, times out, or fails:
     - Gateway durably logs `DECISION: ABORT`.
     - Sends `ABORT` to all prepared participants (releasing reservations).
     - Returns `FAILED: <reason>` to client. Both accounts remain completely unchanged.
2. **After Global Commit Decision:**
   - When both participants vote `VOTE_COMMIT`:
     - Gateway **first** durably records `DECISION: COMMIT` to `transactions.log` and forces an `fsync()` to disk **before** sending `COMMIT` messages.
     - **Irrevocable Invariant:** The transaction can **never** become `ABORT`.
     - Gateway sends `COMMIT` to both participants, retrying up to 3 times on connection drops.
     - **Participant Completion & Failure Semantics:**
       - If both participants acknowledge commit: Client receives `SUCCESS`.
       - If a participant fails/crashes during commit and cannot be reached:
         - The transaction remains **permanently committed** in the coordinator's durable decision log (never rolled back).
         - The coordinator returns an explicit non-success state: `FAILED: COMMIT DECIDED BUT PARTICIPANT UNAVAILABLE (<Server>)`, indicating the global commit was decided but participant delivery could not be confirmed.
         - The decision is retained for manual reconciliation or status inquiry via `RECONCILE <txId>`.
     - **Recovery Scope & Architectural Limitation:**
       - As an academic project with in-memory account state, automatic post-restart reconciliation from `server_{port}_intent.log` into in-memory accounts is not implemented (restarted servers initialize fresh in-memory account state). Participant crash recovery without persistent storage is inherently limited; in-memory accounts on a terminated server cannot automatically be restored.

### 2.3 Concurrency & Deadlock Freedom
- **Thread Pool:** Fixed `ExecutorService` per server process.
- **Account Locking:** Each `Account` possesses an independent `ReentrantLock`.
- **Intra-Server Lock Ordering:** Multi-account operations on the same server lock accounts in strict lexicographical order (`accA.compareTo(accB) < 0`), eliminating circular-wait deadlocks.
- **Distributed Timed Locks:** 2PC participants acquire locks using timed `tryLock(3, TimeUnit.SECONDS)`. If a conflict occurs, the participant votes `VOTE_ABORT LOCK_TIMEOUT`, preventing distributed deadlocks.

### 2.4 Transaction Logging (Section 7 Schema)
Every transaction writes an append-only record to `transactions.log` with the exact required fields:
`timestamp,transactionId,sourceAccount,destinationAccount,amountPaise,status,failureReason`

---

## 3. Project Structure

```
distsysBankS/
├── pom.xml                                    # Maven configuration (Java 21, JUnit 5)
├── README.md                                  # Complete system documentation
├── benchmark_results.csv                      # Measured benchmark results
├── benchmark_results.json                     # Measured benchmark JSON
├── scripts/
│   ├── start-servers.bat / .sh                # Launch Gateway & 3 Bank Servers
│   ├── start-server1.bat                      # Launch BankServer 1 (Port 8001, A1xx)
│   ├── start-server2.bat                      # Launch BankServer 2 (Port 8002, A2xx)
│   ├── start-server3.bat                      # Launch BankServer 3 (Port 8003, A3xx)
│   ├── start-gateway.bat                      # Launch BankGateway (Port 8000)
│   ├── run-client.bat / .sh                   # Launch interactive CLI client
│   ├── run-benchmark.bat / .sh                # Launch performance benchmark
│   └── run-benchmark-suite.ps1                # Automated PowerShell benchmark suite
└── src/
    ├── main/java/com/distsys/bank/
    │   ├── common/
    │   │   ├── Constants.java                 # Ports (8000-8003), timeouts, hosts
    │   │   ├── CurrencyFormatter.java         # Paise <-> Rupee parser and formatter
    │   │   └── TransactionRecord.java         # Section 7 audit log schema
    │   ├── model/
    │   │   └── Account.java                   # Thread-safe account with ReentrantLock
    │   ├── server/
    │   │   ├── BankServer.java                # Participant process entry point
    │   │   ├── AccountRepository.java         # Local account map, 2PC & intent WAL
    │   │   ├── PreparedOperation.java         # Participant prepared operation model
    │   │   └── FailMode.java                  # Failure simulation hooks
    │   ├── gateway/
    │   │   ├── BankGateway.java               # Coordinator process entry point
    │   │   ├── RoutingTable.java              # Prefix router (A1xx, A2xx, A3xx)
    │   │   ├── Coordinator2PC.java            # Two-Phase Commit transaction engine
    │   │   └── TransactionLogger.java         # Append-only WAL with fsync durability
    │   ├── client/
    │   │   └── BankClient.java                # Interactive REPL client
    │   └── benchmark/
    │       └── BenchmarkRunner.java           # Performance harness (CSV/JSON exporter)
    └── test/java/com/distsys/bank/
        ├── EndToEndAssignmentTests.java       # JUnit 5 for Test Cases 1 through 8
        └── ConcurrencyEdgeTests.java           # Deadlock, money conservation & edge tests
```

---

## 4. Build and Run Instructions

### 4.1 Prerequisites
- **Java 21** or later (`java -version`)
- **Maven 3.8+** (or use the included `mvn.cmd` / `mvnw.cmd` wrapper)

### 4.2 Building the Project & Running Tests
To build the project and execute all automated integration tests:

```bash
mvn clean test
# OR on Windows with the included wrapper:
.\mvn.cmd clean test
```

Expected output:
```text
[INFO] Running com.distsys.bank.ConcurrencyEdgeTests
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.distsys.bank.EndToEndAssignmentTests
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

### 4.3 Running the Live System (Demonstration)

#### Step 1: Start the Servers
Open a terminal in the project root:

**On Windows (All servers simultaneously):**
```cmd
scripts\start-servers.bat
```
*(This opens 4 console windows: Server 1 on 8001, Server 2 on 8002, Server 3 on 8003, and Gateway on 8000).*

**On Windows (Individual processes):**
Each distributed process can also be started independently in its own window (either from the project root in `cmd` or by double-clicking the script from Windows Explorer):
- `scripts\start-server1.bat` &mdash; Starts BankServer 1 on port 8001 (partition `A1xx`)
- `scripts\start-server2.bat` &mdash; Starts BankServer 2 on port 8002 (partition `A2xx`)
- `scripts\start-server3.bat` &mdash; Starts BankServer 3 on port 8003 (partition `A3xx`)
- `scripts\start-gateway.bat` &mdash; Starts BankGateway coordinator on port 8000

*(Note: These startup scripts only start or restart the standalone Java process; the application maintains in-memory account state and does not implement automatic post-restart state recovery or reconciliation).*

**On Linux / macOS:**
```bash
bash scripts/start-servers.sh
```

#### Step 2: Start the Client REPL
In another terminal window:

```cmd
scripts\run-client.bat
# OR:
java -cp target/classes com.distsys.bank.client.BankClient
```

#### Step 3: Interactive Commands
```text
bank-client> CREATE A101 Ram 10000
SUCCESS: Account A101 created for Ram with balance 10000.00

bank-client> BALANCE A101
SUCCESS: A101 balance = 10000.00

bank-client> DEPOSIT A101 2000
SUCCESS: Deposited 2000.00 into A101. New balance: 12000.00

bank-client> WITHDRAW A101 3000
SUCCESS: Withdrawn 3000.00 from A101. New balance: 9000.00

bank-client> TRANSFER A101 A201 5000
SUCCESS: Transferred 5000.00 from A101 to A201. TxID: TX-1001

bank-client> HISTORY A101
HISTORY:
[2026-10-06T12:32:00.000Z] TX-1001 | A101 -> A201 | Amount: ₹5,000.00 | Status: SUCCESS
END_OF_HISTORY
```

---

## 5. Verification of Required Test Cases (Section 9)

All 8 test cases from the assignment specification are automated in `EndToEndAssignmentTests.java`:

| # | Test Scenario | Input / Action | Expected Result | Verified Result |
|---|---|---|---|---|
| **1** | Deposit | `A101 = ₹10,000; deposit ₹2,000` | `A101 = ₹12,000` | **PASS** |
| **2** | Withdrawal | `A101 = ₹10,000; withdraw ₹3,000` | `A101 = ₹7,000` | **PASS** |
| **3** | Insufficient Balance | `A101 = ₹10,000; withdraw ₹15,000` | `FAILED: Insufficient funds` (Balance = ₹10,000) | **PASS** |
| **4** | Successful Distributed Transfer | `A101 = ₹10,000; A201 = ₹20,000; transfer ₹5,000` | `A101 = ₹5,000; A201 = ₹25,000` | **PASS** |
| **5** | Concurrent Transfers | 10 concurrent threads issuing 200 simultaneous transfers on `A101` | Final balance matches exact sum; no lost updates | **PASS** |
| **6** | Concurrent Withdrawal | `A101 = ₹10,000; T1: withdraw ₹7,000; T2: withdraw ₹7,000` | Exactly 1 succeeds, 1 fails; balance = ₹3,000 | **PASS** |
| **7** | Server Failure | Participant fails during prepare or is offline | `FAILED`; source balance remains untouched | **PASS** |
| **8** | Invalid Account | `TRANSFER A101 A999 5000` | `FAILED: Destination account not found` | **PASS** |

---

## 6. Real Performance Benchmark Results (Section 8)

The benchmark was executed using the actual implementation via `BenchmarkRunner` against the running system.
**Workload Composition:** The benchmark evaluates a **mixed banking workload** (20% `BALANCE` queries, 20% `DEPOSIT`, 20% `WITHDRAW`, and 40% cross-server Two-Phase Commit `TRANSFER` operations) comparing sequential single-client execution against concurrent multi-threaded execution.

Raw data is exported to `benchmark_results.csv` and `benchmark_results.json`:

| Execution Mode | Workload (Requests) | Concurrency (Threads) | Total Elapsed Time (ms) | Avg Latency (ms) | Median p50 (ms) | 95th Percentile p95 (ms) | Throughput (req/sec) | Success Count | Failed Count |
|---|---|---|---|---|---|---|---|---|---|
| **Sequential** | 10 | 1 | 124.26 | 11.90 | 9.00 | 64.00 | 80.47 | 10 | 0 |
| **Sequential** | 50 | 1 | 226.70 | 4.02 | 2.00 | 9.00 | 220.56 | 50 | 0 |
| **Sequential** | 100 | 1 | 390.97 | 3.37 | 2.00 | 7.00 | 255.78 | 100 | 0 |
| **Sequential** | 500 | 1 | 1659.97 | 2.83 | 1.00 | 7.00 | 301.21 | 500 | 0 |
| **Sequential** | 1000 | 1 | 3025.52 | 2.53 | 1.00 | 7.00 | 330.52 | 1000 | 0 |
| **Concurrent** | 10 | 2 | 23.20 | 3.40 | 2.00 | 6.00 | 431.01 | 10 | 0 |
| **Concurrent** | 50 | 10 | 38.42 | 6.26 | 4.00 | 13.00 | 1301.25 | 50 | 0 |
| **Concurrent** | 100 | 20 | 88.50 | 13.69 | 10.00 | 25.00 | 1129.96 | 100 | 0 |
| **Concurrent** | 500 | 20 | 464.92 | 16.82 | 12.00 | 34.00 | 1075.45 | 500 | 0 |
| **Concurrent** | 1000 | 20 | 854.88 | 16.49 | 14.00 | 36.00 | 1169.75 | 1000 | 0 |

To re-run the benchmark suite:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-benchmark-suite.ps1
```

---

## 7. 5–7 Minute Demonstration Run-Book (Section 10)

1. **Topology & Setup (0:00–1:00):** Run `scripts\start-servers.bat`. Highlight 4 running processes: Gateway (8000), S1 (8001, `A1xx`), S2 (8002, `A2xx`), S3 (8003, `A3xx`).
2. **Normal Operations (1:00–2:00):** Run `scripts\run-client.bat`. Demonstrate `CREATE`, `DEPOSIT`, `WITHDRAW`, `BALANCE`.
3. **Cross-Server 2PC Transfer (2:00–3:15):** Execute `TRANSFER A101 A201 5000`. Show Gateway console displaying `PREPARE_DEBIT` $\to$ `PREPARE_CREDIT` $\to$ `DURABLE COMMIT` $\to$ participant commits.
4. **Concurrent Transactions (3:15–4:15):** Run Test Case 6: simultaneous withdrawals of ₹7,000 on account with ₹10,000. Show one succeeds and one fails, balance = ₹3,000.
5. **Failure Simulation & Atomicity (4:15–5:30):** Terminate Server 2 window (`Ctrl+C`). Attempt `TRANSFER A101 A201 1000`. Show transaction aborts with `Destination server unavailable`, source balance remains intact. Show that pre-decision failure leaves accounts completely untouched.
6. **Audit & Log Verification (5:30–6:30):** Run `HISTORY A101` and view `transactions.log`. Verify all required Section 7 fields.
