package com.distsys.bank.gateway;

import com.distsys.bank.common.Constants;
import com.distsys.bank.common.TransactionRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only durable transaction logger.
 * Implements write-ahead logging for coordinator decisions with explicit disk sync.
 * Satisfies Section 7 and 2PC durability requirements.
 */
public class TransactionLogger {
    private final File logFile;
    private FileOutputStream fileOutputStream;
    private final List<TransactionRecord> inMemoryRecords = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, String> decisions = new ConcurrentHashMap<>();

    public TransactionLogger() {
        this(new File(Constants.LOG_FILE_PATH));
    }

    public TransactionLogger(File logFile) {
        this.logFile = logFile;
        initFile();
        loadExistingRecords();
    }

    private synchronized void initFile() {
        try {
            if (!logFile.exists()) {
                File parent = logFile.getParentFile();
                if (parent != null) parent.mkdirs();
                logFile.createNewFile();
            }
            this.fileOutputStream = new FileOutputStream(logFile, true);
        } catch (IOException e) {
            throw new RuntimeException("Could not open transaction log file: " + logFile, e);
        }
    }

    private synchronized void loadExistingRecords() {
        if (!logFile.exists() || logFile.length() == 0) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("DECISION:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        decisions.put(parts[2], parts[1]); // e.g. DECISION: COMMIT TX-1001
                    }
                } else if (line.contains(",")) {
                    try {
                        TransactionRecord record = TransactionRecord.fromCsvLine(line);
                        inMemoryRecords.add(record);
                        if ("SUCCESS".equalsIgnoreCase(record.status())) {
                            decisions.put(record.transactionId(), "COMMIT");
                        } else {
                            // If a DECISION: COMMIT was already recorded earlier in the log,
                            // do NOT overwrite it with ABORT. A COMMIT decision is irrevocable.
                            decisions.putIfAbsent(record.transactionId(), "ABORT");
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not read existing log file: " + e.getMessage());
        }
    }

    /**
     * Durably logs the global 2PC decision (COMMIT or ABORT) to disk BEFORE sending commands to participants.
     * Enforces hard disk flush (fsync) to satisfy durability invariant.
     */
    public synchronized void recordDurableDecision(String txId, String decision) {
        decisions.put(txId, decision.toUpperCase());
        try {
            String line = "DECISION: " + decision.toUpperCase() + " " + txId + "\n";
            fileOutputStream.write(line.getBytes(StandardCharsets.UTF_8));
            fileOutputStream.flush();
            fileOutputStream.getFD().sync(); // Hard sync to disk
        } catch (IOException e) {
            throw new RuntimeException("CRITICAL: Failed to force durable write of decision " + decision + " for " + txId, e);
        }
    }

    /**
     * Durably appends a completed transaction record to the log.
     */
    public synchronized void recordTransaction(TransactionRecord record) {
        inMemoryRecords.add(record);
        try {
            String line = record.toCsvLine() + "\n";
            fileOutputStream.write(line.getBytes(StandardCharsets.UTF_8));
            fileOutputStream.flush();
            fileOutputStream.getFD().sync(); // Hard sync to disk
        } catch (IOException e) {
            System.err.println("CRITICAL: Failed to persist transaction record: " + e.getMessage());
        }
    }

    public String getDecision(String txId) {
        return decisions.getOrDefault(txId, "UNKNOWN");
    }

    public List<TransactionRecord> getHistoryForAccount(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return Collections.unmodifiableList(inMemoryRecords);
        }
        String target = accountId.trim();
        List<TransactionRecord> matched = new ArrayList<>();
        for (TransactionRecord record : inMemoryRecords) {
            if (target.equalsIgnoreCase(record.sourceAccount()) || target.equalsIgnoreCase(record.destinationAccount())) {
                matched.add(record);
            }
        }
        return matched;
    }

    public List<TransactionRecord> getAllRecords() {
        return Collections.unmodifiableList(inMemoryRecords);
    }

    public synchronized void close() {
        try {
            if (fileOutputStream != null) {
                fileOutputStream.flush();
                fileOutputStream.close();
            }
        } catch (IOException ignored) {}
    }

    public synchronized void clearForTesting() {
        inMemoryRecords.clear();
        decisions.clear();
        close();
        if (logFile.exists()) {
            logFile.delete();
        }
        initFile();
    }
}
