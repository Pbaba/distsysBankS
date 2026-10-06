package com.distsys.bank.server;

import com.distsys.bank.common.Constants;
import com.distsys.bank.model.Account;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bank Server participant process.
 * Listens on a dedicated TCP port, executes localized account operations,
 * and participates in Two-Phase Commit transactions.
 */
public class BankServer implements Runnable {
    private final String serverId;
    private final int port;
    private final AccountRepository repository;
    private final ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private volatile FailMode currentFailMode = FailMode.NONE;

    public BankServer(String serverId, int port) {
        this.serverId = serverId;
        this.port = port;
        this.repository = new AccountRepository(serverId, port);
        this.threadPool = Executors.newFixedThreadPool(Constants.THREAD_POOL_SIZE);
    }

    public AccountRepository getRepository() {
        return repository;
    }

    public void setFailMode(FailMode mode) {
        this.currentFailMode = mode != null ? mode : FailMode.NONE;
    }

    public FailMode getFailMode() {
        return currentFailMode;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running.set(true);
        Thread listenerThread = new Thread(this, "BankServer-" + port);
        listenerThread.start();
        System.out.println("[" + serverId + "] Started listening on port " + port);
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (!running.get()) break;
                System.err.println("[" + serverId + "] Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            // Failure hook: crash before reading command
            if (currentFailMode == FailMode.CRASH_BEFORE_TRANSACTION) {
                System.out.println("[" + serverId + "] Triggered failure hook: CRASH_BEFORE_TRANSACTION. Closing socket.");
                currentFailMode = FailMode.NONE;
                return;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String response = processCommand(line);

                // Failure hook: crash during commit
                if (currentFailMode == FailMode.CRASH_DURING_COMMIT && line.startsWith("COMMIT ")) {
                    System.out.println("[" + serverId + "] Triggered failure hook: CRASH_DURING_COMMIT. Closing socket.");
                    currentFailMode = FailMode.NONE;
                    return;
                }

                writer.println(response);

                if ("SHUTDOWN_OK".equals(response)) {
                    stop();
                    break;
                }
            }
        } catch (IOException ignored) {
            // Client disconnected or connection dropped
        }
    }

    public String processCommand(String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        String cmd = parts[0].toUpperCase();

        try {
            switch (cmd) {
                case "PING":
                    return "PONG";

                case "CREATE": {
                    if (parts.length < 4) return "ERR Usage: CREATE <accId> <owner> <paise>";
                    String accId = parts[1];
                    String owner = parts[2];
                    long paise = Long.parseLong(parts[3]);
                    repository.createAccount(accId, owner, paise);
                    return "OK Account " + accId + " created";
                }

                case "BALANCE": {
                    if (parts.length < 2) return "ERR Usage: BALANCE <accId>";
                    String accId = parts[1];
                    long bal = repository.getBalance(accId);
                    return "OK " + bal;
                }

                case "DEPOSIT": {
                    if (parts.length < 3) return "ERR Usage: DEPOSIT <accId> <paise>";
                    String accId = parts[1];
                    long amount = Long.parseLong(parts[2]);
                    long newBal = repository.deposit(accId, amount);
                    return "OK " + newBal;
                }

                case "WITHDRAW": {
                    if (parts.length < 3) return "ERR Usage: WITHDRAW <accId> <paise>";
                    String accId = parts[1];
                    long amount = Long.parseLong(parts[2]);
                    long newBal = repository.withdraw(accId, amount);
                    return "OK " + newBal;
                }

                case "LOCAL_TRANSFER": {
                    if (parts.length < 4) return "ERR Usage: LOCAL_TRANSFER <src> <dst> <paise>";
                    String srcId = parts[1];
                    String dstId = parts[2];
                    long amount = Long.parseLong(parts[3]);
                    repository.localTransfer(srcId, dstId, amount);
                    return "OK Transfer completed";
                }

                case "PREPARE_DEBIT": {
                    if (parts.length < 4) return "VOTE_ABORT MALFORMED_COMMAND";
                    if (currentFailMode == FailMode.CRASH_DURING_PREPARE) {
                        currentFailMode = FailMode.NONE;
                        return "VOTE_ABORT SIMULATED_FAILURE";
                    }
                    String txId = parts[1];
                    String accId = parts[2];
                    long amount = Long.parseLong(parts[3]);
                    boolean ok = repository.prepareDebit(txId, accId, amount);
                    return ok ? "VOTE_COMMIT" : "VOTE_ABORT INSUFFICIENT_FUNDS_OR_LOCK_FAILED";
                }

                case "PREPARE_CREDIT": {
                    if (parts.length < 4) return "VOTE_ABORT MALFORMED_COMMAND";
                    if (currentFailMode == FailMode.CRASH_DURING_PREPARE) {
                        currentFailMode = FailMode.NONE;
                        return "VOTE_ABORT SIMULATED_FAILURE";
                    }
                    String txId = parts[1];
                    String accId = parts[2];
                    long amount = Long.parseLong(parts[3]);
                    boolean ok = repository.prepareCredit(txId, accId, amount);
                    return ok ? "VOTE_COMMIT" : "VOTE_ABORT ACCOUNT_NOT_FOUND";
                }

                case "COMMIT": {
                    if (parts.length < 2) return "ERR Usage: COMMIT <txId>";
                    String txId = parts[1];
                    boolean ok = repository.commit(txId);
                    return ok ? "COMMITTED" : "COMMITTED_ALREADY_OR_UNKNOWN";
                }

                case "ABORT": {
                    if (parts.length < 2) return "ERR Usage: ABORT <txId>";
                    String txId = parts[1];
                    repository.abort(txId);
                    return "ABORTED";
                }

                case "STATUS":
                case "RECONCILE": {
                    if (parts.length < 2) return "ERR Usage: RECONCILE <txId>";
                    String txId = parts[1];
                    PreparedOperation op = repository.getPreparedOp(txId);
                    if (op != null) {
                        return "PREPARED " + op.accountId() + " " + op.opType() + " " + op.amountPaise();
                    }
                    return "NOT_PENDING";
                }

                case "SET_FAIL_HOOK": {
                    if (parts.length < 2) return "ERR Usage: SET_FAIL_HOOK <mode>";
                    setFailMode(FailMode.valueOf(parts[1].toUpperCase()));
                    return "OK FAIL_HOOK_SET_" + currentFailMode;
                }

                case "RESET_FAIL_HOOK": {
                    setFailMode(FailMode.NONE);
                    return "OK FAIL_HOOK_RESET";
                }

                case "SHUTDOWN": {
                    return "SHUTDOWN_OK";
                }

                default:
                    return "ERR UNKNOWN_COMMAND " + cmd;
            }
        } catch (IllegalStateException | NoSuchElementException | IllegalArgumentException e) {
            return "ERR " + e.getMessage();
        } catch (Exception e) {
            return "ERR INTERNAL_ERROR: " + e.getMessage();
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        threadPool.shutdownNow();
        repository.close();
        System.out.println("[" + serverId + "] Stopped.");
    }

    public static void main(String[] args) {
        int port = Constants.SERVER_1_PORT;
        String id = Constants.SERVER_1_ID;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            id = args[1];
        } else {
            if (port == Constants.SERVER_1_PORT) id = Constants.SERVER_1_ID;
            else if (port == Constants.SERVER_2_PORT) id = Constants.SERVER_2_ID;
            else if (port == Constants.SERVER_3_PORT) id = Constants.SERVER_3_ID;
            else id = "Server-" + port;
        }

        try {
            BankServer server = new BankServer(id, port);
            server.start();

            // Default seed accounts as specified in assignment §2
            if (port == Constants.SERVER_1_PORT) {
                server.getRepository().createAccount("A101", "Ram", 1000000L); // ₹10,000
                server.getRepository().createAccount("A102", "Sita", 1000000L);
            } else if (port == Constants.SERVER_2_PORT) {
                server.getRepository().createAccount("A201", "Lakshman", 2000000L); // ₹20,000
                server.getRepository().createAccount("A202", "Urmila", 2000000L);
            } else if (port == Constants.SERVER_3_PORT) {
                server.getRepository().createAccount("A301", "Bharat", 1500000L); // ₹15,000
                server.getRepository().createAccount("A302", "Mandavi", 1500000L);
            }

            System.out.println("[" + id + "] Ready with initial accounts. Press Ctrl+C to terminate.");
        } catch (IOException e) {
            System.err.println("Fatal error starting " + id + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
