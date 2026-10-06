package com.distsys.bank.server;

/**
 * Represents a pending Two-Phase Commit operation held at a participant bank server.
 */
public record PreparedOperation(
        String txId,
        String accountId,
        OpType opType,
        long amountPaise,
        String timestamp
) {
    public enum OpType {
        DEBIT,
        CREDIT
    }

    public String toLogLine(String state) {
        return String.join(",", state, txId, accountId, opType.name(), String.valueOf(amountPaise), timestamp);
    }
}
