package com.distsys.bank.gateway;

import com.distsys.bank.common.Constants;
import com.distsys.bank.common.TransactionRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two-Phase Commit (2PC) Coordinator for cross-server money transfers.
 * Strictly enforces:
 * 1. Before decision: any error/timeout aborts transaction cleanly.
 * 2. After decision: once COMMIT is durably logged, the decision is irrevocable and retried if a participant fails.
 */
public class Coordinator2PC {
    public record TransferResult(boolean success, String txId, String message) {
        public static TransferResult success(String txId, String msg) {
            return new TransferResult(true, txId, msg);
        }
        public static TransferResult failed(String txId, String msg) {
            return new TransferResult(false, txId, msg);
        }
    }

    private final RoutingTable routingTable;
    private final TransactionLogger transactionLogger;
    private final AtomicLong txCounter = new AtomicLong(1000);

    public Coordinator2PC(RoutingTable routingTable, TransactionLogger transactionLogger) {
        this.routingTable = routingTable;
        this.transactionLogger = transactionLogger;
    }

    public TransferResult executeTransfer(String sourceAcc, String destAcc, long amountPaise) {
        String txId = "TX-" + txCounter.incrementAndGet();

        RoutingTable.ServerEndpoint srcEp = routingTable.getEndpointForAccount(sourceAcc);
        RoutingTable.ServerEndpoint dstEp = routingTable.getEndpointForAccount(destAcc);

        if (srcEp == null) {
            String err = "Source account not found";
            transactionLogger.recordTransaction(TransactionRecord.failure(txId, sourceAcc, destAcc, amountPaise, err));
            return TransferResult.failed(txId, err);
        }
        if (dstEp == null) {
            String err = "Destination account not found";
            transactionLogger.recordTransaction(TransactionRecord.failure(txId, sourceAcc, destAcc, amountPaise, err));
            return TransferResult.failed(txId, err);
        }

        // Case 1: Intra-server transfer (same participant server)
        if (srcEp.port() == dstEp.port()) {
            return executeIntraServerTransfer(txId, srcEp, sourceAcc, destAcc, amountPaise);
        }

        // Case 2: Cross-server distributed 2PC transfer
        return executeCrossServerTransfer(txId, srcEp, dstEp, sourceAcc, destAcc, amountPaise);
    }

    private TransferResult executeIntraServerTransfer(
            String txId, RoutingTable.ServerEndpoint endpoint, String sourceAcc, String destAcc, long amountPaise) {
        try {
            String resp = sendRpc(endpoint, "LOCAL_TRANSFER " + sourceAcc + " " + destAcc + " " + amountPaise, Constants.SOCKET_TIMEOUT_MS);
            if (resp != null && resp.startsWith("OK")) {
                transactionLogger.recordTransaction(TransactionRecord.success(txId, sourceAcc, destAcc, amountPaise));
                return TransferResult.success(txId, "Transferred successfully on " + endpoint.serverId());
            } else {
                String err = parseErrorMessage(resp, "Local transfer failed");
                transactionLogger.recordTransaction(TransactionRecord.failure(txId, sourceAcc, destAcc, amountPaise, err));
                return TransferResult.failed(txId, err);
            }
        } catch (IOException e) {
            String err = "Server " + endpoint.serverId() + " unavailable";
            transactionLogger.recordTransaction(TransactionRecord.failure(txId, sourceAcc, destAcc, amountPaise, err));
            return TransferResult.failed(txId, err);
        }
    }

    private TransferResult executeCrossServerTransfer(
            String txId, RoutingTable.ServerEndpoint srcEp, RoutingTable.ServerEndpoint dstEp,
            String sourceAcc, String destAcc, long amountPaise) {

        boolean srcPrepared = false;
        boolean dstPrepared = false;
        String abortReason = null;

        // -------------------------------------------------------------
        // STAGE 1: PREPARE PHASE (Voting)
        // -------------------------------------------------------------

        // Step 1a: Prepare Source (Debit hold)
        try {
            String resp = sendRpc(srcEp, "PREPARE_DEBIT " + txId + " " + sourceAcc + " " + amountPaise, Constants.SOCKET_TIMEOUT_MS);
            if ("VOTE_COMMIT".equals(resp)) {
                srcPrepared = true;
            } else {
                abortReason = parseErrorMessage(resp, "Insufficient funds or source account not found");
            }
        } catch (IOException e) {
            abortReason = "Source server unavailable (" + srcEp.serverId() + ")";
        }

        // Step 1b: Prepare Destination (Credit hold/verification)
        if (srcPrepared) {
            try {
                String resp = sendRpc(dstEp, "PREPARE_CREDIT " + txId + " " + destAcc + " " + amountPaise, Constants.SOCKET_TIMEOUT_MS);
                if ("VOTE_COMMIT".equals(resp)) {
                    dstPrepared = true;
                } else {
                    abortReason = parseErrorMessage(resp, "Destination account not found");
                }
            } catch (IOException e) {
                abortReason = "Destination server unavailable (" + dstEp.serverId() + ")";
            }
        }

        // -------------------------------------------------------------
        // STAGE 2: GLOBAL DECISION
        // -------------------------------------------------------------

        // STATE A: BEFORE GLOBAL DECISION - If either failed to vote commit -> DECIDE ABORT
        if (!srcPrepared || !dstPrepared) {
            // Coordinator durably records ABORT decision
            transactionLogger.recordDurableDecision(txId, "ABORT");
            transactionLogger.recordTransaction(TransactionRecord.failure(txId, sourceAcc, destAcc, amountPaise, abortReason));

            // Roll back any prepared participant
            if (srcPrepared) {
                trySendBestEffort(srcEp, "ABORT " + txId);
            }
            if (dstPrepared) {
                trySendBestEffort(dstEp, "ABORT " + txId);
            }

            return TransferResult.failed(txId, abortReason);
        }

        // STATE B: AFTER GLOBAL COMMIT DECISION
        // Both voted VOTE_COMMIT.
        // DURABLE FLUSH FIRST: Coordinator writes DECISION: COMMIT to disk before sending messages.
        transactionLogger.recordDurableDecision(txId, "COMMIT");

        // The decision is now IRREVOCABLE. It can NEVER be changed to ABORT.
        // Commit Phase: Deliver COMMIT to both participants, retrying if necessary.
        boolean srcCommitted = sendWithRetry(srcEp, "COMMIT " + txId, 3);
        boolean dstCommitted = sendWithRetry(dstEp, "COMMIT " + txId, 3);

        if (srcCommitted && dstCommitted) {
            transactionLogger.recordTransaction(TransactionRecord.success(txId, sourceAcc, destAcc, amountPaise));
            return TransferResult.success(txId, "Transferred successfully across servers");
        } else {
            // One or both participants failed during commit. Invariant: DO NOT ABORT.
            // Transaction decision is durably recorded as COMMIT; participant completion was not confirmed.
            String unavailableParticipant = !srcCommitted ? srcEp.serverId() : dstEp.serverId();
            String errMsg = "COMMIT DECIDED BUT PARTICIPANT UNAVAILABLE (" + unavailableParticipant + ")";
            transactionLogger.recordTransaction(TransactionRecord.failure(txId, sourceAcc, destAcc, amountPaise, errMsg));
            return TransferResult.failed(txId, errMsg);
        }
    }

    private boolean sendWithRetry(RoutingTable.ServerEndpoint endpoint, String command, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                String resp = sendRpc(endpoint, command, Constants.SOCKET_TIMEOUT_MS);
                if (resp != null && (resp.startsWith("COMMITTED") || resp.startsWith("OK"))) {
                    return true;
                }
            } catch (IOException ignored) {}
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    private void trySendBestEffort(RoutingTable.ServerEndpoint endpoint, String command) {
        try {
            sendRpc(endpoint, command, 1000);
        } catch (IOException ignored) {}
    }

    public String sendRpc(RoutingTable.ServerEndpoint endpoint, String command, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            writer.println(command);
            return reader.readLine();
        }
    }

    private String parseErrorMessage(String resp, String defaultMsg) {
        if (resp == null) return defaultMsg;
        if (resp.startsWith("ERR ")) return resp.substring(4);
        if (resp.startsWith("VOTE_ABORT ")) {
            String reason = resp.substring(11);
            if ("INSUFFICIENT_FUNDS_OR_LOCK_FAILED".equalsIgnoreCase(reason)) return "Insufficient balance";
            if ("ACCOUNT_NOT_FOUND".equalsIgnoreCase(reason)) return "Destination account not found";
            return reason;
        }
        return defaultMsg;
    }
}
