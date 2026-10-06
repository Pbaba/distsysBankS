package com.distsys.bank.common;

import java.time.Instant;

/**
 * Immutable transaction record satisfying assignment Section 7 requirements.
 * Fields:
 * - Transaction ID
 * - Source account
 * - Destination account
 * - Amount (formatted string or long paise)
 * - Timestamp (ISO-8601 UTC)
 * - Status (SUCCESS / FAILED)
 * - Failure reason, if any
 */
public record TransactionRecord(
        String transactionId,
        String sourceAccount,
        String destinationAccount,
        long amountPaise,
        String timestamp,
        String status,
        String failureReason
) {
    public static TransactionRecord success(String txId, String source, String dest, long amountPaise) {
        return new TransactionRecord(
                txId,
                source != null ? source : "N/A",
                dest != null ? dest : "N/A",
                amountPaise,
                Instant.now().toString(),
                "SUCCESS",
                "NONE"
        );
    }

    public static TransactionRecord failure(String txId, String source, String dest, long amountPaise, String reason) {
        return new TransactionRecord(
                txId,
                source != null ? source : "N/A",
                dest != null ? dest : "N/A",
                amountPaise,
                Instant.now().toString(),
                "FAILED",
                reason != null ? reason : "Unknown error"
        );
    }

    public String toCsvLine() {
        return String.join(",",
                transactionId,
                sourceAccount,
                destinationAccount,
                String.valueOf(amountPaise),
                timestamp,
                status,
                escapeCsv(failureReason)
        );
    }

    public static TransactionRecord fromCsvLine(String line) {
        String[] parts = line.split(",", 7);
        if (parts.length < 7) {
            throw new IllegalArgumentException("Malformed transaction log line: " + line);
        }
        return new TransactionRecord(
                parts[0],
                parts[1],
                parts[2],
                Long.parseLong(parts[3]),
                parts[4],
                parts[5],
                parts[6]
        );
    }

    private static String escapeCsv(String str) {
        if (str == null) return "NONE";
        return str.replace(",", ";");
    }

    public String formatUserSummary() {
        String amt = CurrencyFormatter.formatDisplay(amountPaise);
        if ("SUCCESS".equalsIgnoreCase(status)) {
            return String.format("[%s] %s | %s -> %s | Amount: %s | Status: SUCCESS",
                    timestamp, transactionId, sourceAccount, destinationAccount, amt);
        } else {
            return String.format("[%s] %s | %s -> %s | Amount: %s | Status: FAILED (%s)",
                    timestamp, transactionId, sourceAccount, destinationAccount, amt, failureReason);
        }
    }
}
