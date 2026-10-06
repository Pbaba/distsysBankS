package com.distsys.bank.gateway;

import com.distsys.bank.common.Constants;
import com.distsys.bank.common.CurrencyFormatter;
import com.distsys.bank.common.TransactionRecord;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bank Gateway / Coordinator process.
 * Acts as the single entry point for all clients.
 * Routes single-account queries to responsible servers and coordinates
 * distributed Two-Phase Commit transfers.
 */
public class BankGateway implements Runnable {
    private final int port;
    private final RoutingTable routingTable;
    private final TransactionLogger transactionLogger;
    private final Coordinator2PC coordinator2PC;
    private final ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public BankGateway() {
        this(Constants.GATEWAY_PORT);
    }

    public BankGateway(int port) {
        this(port, new RoutingTable(), new TransactionLogger());
    }

    public BankGateway(int port, RoutingTable routingTable, TransactionLogger transactionLogger) {
        this.port = port;
        this.routingTable = routingTable;
        this.transactionLogger = transactionLogger;
        this.coordinator2PC = new Coordinator2PC(routingTable, transactionLogger);
        this.threadPool = Executors.newFixedThreadPool(Constants.THREAD_POOL_SIZE);
    }

    public RoutingTable getRoutingTable() {
        return routingTable;
    }

    public TransactionLogger getTransactionLogger() {
        return transactionLogger;
    }

    public Coordinator2PC getCoordinator2PC() {
        return coordinator2PC;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running.set(true);
        Thread listenerThread = new Thread(this, "BankGateway-" + port);
        listenerThread.start();
        System.out.println("[BankGateway] Started listening on port " + port);
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (!running.get()) break;
                System.err.println("[BankGateway] Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String response = processCommand(line);
                writer.println(response);

                if ("SHUTDOWN_OK".equals(response)) {
                    stop();
                    break;
                }
            }
        } catch (IOException ignored) {}
    }

    public String processCommand(String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        String cmd = parts[0].toUpperCase();

        try {
            switch (cmd) {
                case "PING":
                    return "PONG";

                case "CREATE": {
                    // CREATE <accId> <owner> <initialBalance>
                    if (parts.length < 4) return "FAILED: Usage: CREATE <accId> <owner> <initialBalance>";
                    String accId = parts[1];
                    String owner = parts[2];
                    long paise = CurrencyFormatter.parsePaise(parts[3]);

                    RoutingTable.ServerEndpoint ep = routingTable.getEndpointForAccount(accId);
                    if (ep == null) return "FAILED: Account prefix not recognized or invalid";

                    String rpcResp = coordinator2PC.sendRpc(ep, "CREATE " + accId + " " + owner + " " + paise, Constants.SOCKET_TIMEOUT_MS);
                    if (rpcResp != null && rpcResp.startsWith("OK")) {
                        return "SUCCESS: Account " + accId + " created for " + owner + " with balance " + CurrencyFormatter.formatPaise(paise);
                    } else {
                        return "FAILED: " + extractErrorMessage(rpcResp);
                    }
                }

                case "BALANCE": {
                    // BALANCE <accId>
                    if (parts.length < 2) return "FAILED: Usage: BALANCE <accId>";
                    String accId = parts[1];
                    RoutingTable.ServerEndpoint ep = routingTable.getEndpointForAccount(accId);
                    if (ep == null) return "FAILED: Destination account not found";

                    String rpcResp = coordinator2PC.sendRpc(ep, "BALANCE " + accId, Constants.SOCKET_TIMEOUT_MS);
                    if (rpcResp != null && rpcResp.startsWith("OK")) {
                        long balPaise = Long.parseLong(rpcResp.substring(3).trim());
                        return "SUCCESS: " + accId + " balance = " + CurrencyFormatter.formatPaise(balPaise);
                    } else {
                        return "FAILED: " + extractErrorMessage(rpcResp);
                    }
                }

                case "DEPOSIT": {
                    // DEPOSIT <accId> <amount>
                    if (parts.length < 3) return "FAILED: Usage: DEPOSIT <accId> <amount>";
                    String accId = parts[1];
                    long paise = CurrencyFormatter.parsePaise(parts[2]);

                    RoutingTable.ServerEndpoint ep = routingTable.getEndpointForAccount(accId);
                    if (ep == null) return "FAILED: Destination account not found";

                    String rpcResp = coordinator2PC.sendRpc(ep, "DEPOSIT " + accId + " " + paise, Constants.SOCKET_TIMEOUT_MS);
                    if (rpcResp != null && rpcResp.startsWith("OK")) {
                        long newBal = Long.parseLong(rpcResp.substring(3).trim());
                        return "SUCCESS: Deposited " + CurrencyFormatter.formatPaise(paise) + " into " + accId + ". New balance: " + CurrencyFormatter.formatPaise(newBal);
                    } else {
                        return "FAILED: " + extractErrorMessage(rpcResp);
                    }
                }

                case "WITHDRAW": {
                    // WITHDRAW <accId> <amount>
                    if (parts.length < 3) return "FAILED: Usage: WITHDRAW <accId> <amount>";
                    String accId = parts[1];
                    long paise = CurrencyFormatter.parsePaise(parts[2]);

                    RoutingTable.ServerEndpoint ep = routingTable.getEndpointForAccount(accId);
                    if (ep == null) return "FAILED: Destination account not found";

                    String rpcResp = coordinator2PC.sendRpc(ep, "WITHDRAW " + accId + " " + paise, Constants.SOCKET_TIMEOUT_MS);
                    if (rpcResp != null && rpcResp.startsWith("OK")) {
                        long newBal = Long.parseLong(rpcResp.substring(3).trim());
                        return "SUCCESS: Withdrawn " + CurrencyFormatter.formatPaise(paise) + " from " + accId + ". New balance: " + CurrencyFormatter.formatPaise(newBal);
                    } else {
                        return "FAILED: " + extractErrorMessage(rpcResp);
                    }
                }

                case "TRANSFER": {
                    // TRANSFER <sourceAccount> <destinationAccount> <amount>
                    if (parts.length < 4) return "FAILED: Usage: TRANSFER <sourceAccount> <destinationAccount> <amount>";
                    String src = parts[1];
                    String dst = parts[2];
                    long paise = CurrencyFormatter.parsePaise(parts[3]);

                    Coordinator2PC.TransferResult result = coordinator2PC.executeTransfer(src, dst, paise);
                    if (result.success()) {
                        return "SUCCESS: Transferred " + CurrencyFormatter.formatPaise(paise) + " from " + src + " to " + dst + ". TxID: " + result.txId();
                    } else {
                        return "FAILED: " + result.message();
                    }
                }

                case "HISTORY": {
                    // HISTORY [accId]
                    String accId = parts.length > 1 ? parts[1] : null;
                    List<TransactionRecord> history = transactionLogger.getHistoryForAccount(accId);
                    if (history.isEmpty()) {
                        return "HISTORY: No transactions found.\nEND_OF_HISTORY";
                    }
                    StringBuilder sb = new StringBuilder("HISTORY:\n");
                    for (TransactionRecord r : history) {
                        sb.append(r.formatUserSummary()).append("\n");
                    }
                    sb.append("END_OF_HISTORY");
                    return sb.toString();
                }

                case "RECONCILE": {
                    // RECONCILE <txId>
                    if (parts.length < 2) return "FAILED: Usage: RECONCILE <txId>";
                    String txId = parts[1];
                    String decision = transactionLogger.getDecision(txId);
                    return "DECISION:" + decision;
                }

                case "SHUTDOWN": {
                    return "SHUTDOWN_OK";
                }

                default:
                    return "FAILED: Unknown command " + cmd;
            }
        } catch (IllegalArgumentException e) {
            return "FAILED: " + e.getMessage();
        } catch (IOException e) {
            return "FAILED: Bank server communication error: " + e.getMessage();
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    private String extractErrorMessage(String resp) {
        if (resp == null) return "Server response timeout or empty";
        if (resp.startsWith("ERR ")) return resp.substring(4);
        return resp;
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        threadPool.shutdownNow();
        transactionLogger.close();
        System.out.println("[BankGateway] Stopped.");
    }

    public static void main(String[] args) {
        int port = Constants.GATEWAY_PORT;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        try {
            BankGateway gateway = new BankGateway(port);
            gateway.start();
            System.out.println("[BankGateway] Gateway is running on port " + port + ". Ready for client connections.");
        } catch (IOException e) {
            System.err.println("Fatal error starting BankGateway: " + e.getMessage());
            System.exit(1);
        }
    }
}
