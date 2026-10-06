package com.distsys.bank.server;

import com.distsys.bank.model.Account;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe account storage and 2PC participant state engine for a BankServer.
 * Persists participant transaction intent to disk for true crash recovery.
 */
public class AccountRepository {
    private final String serverId;
    private final int port;
    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PreparedOperation> preparedOps = new ConcurrentHashMap<>();
    private final File intentLogFile;
    private FileOutputStream logOutputStream;

    public AccountRepository(String serverId, int port) {
        this.serverId = serverId;
        this.port = port;
        this.intentLogFile = new File("server_" + port + "_intent.log");
        initLogFile();
    }

    private synchronized void initLogFile() {
        try {
            if (!intentLogFile.exists()) {
                File parent = intentLogFile.getParentFile();
                if (parent != null) parent.mkdirs();
                intentLogFile.createNewFile();
            }
            this.logOutputStream = new FileOutputStream(intentLogFile, true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize participant intent log: " + intentLogFile, e);
        }
    }

    private synchronized boolean appendIntent(String state, PreparedOperation op) {
        try {
            if (logOutputStream == null) return false;
            String line = op.toLogLine(state) + "\n";
            logOutputStream.write(line.getBytes(StandardCharsets.UTF_8));
            logOutputStream.flush();
            logOutputStream.getFD().sync(); // Durable flush to disk
            return true;
        } catch (IOException e) {
            System.err.println("[" + serverId + "] Failed to flush intent log: " + e.getMessage());
            return false;
        }
    }

    private synchronized boolean appendResolution(String state, String txId) {
        try {
            if (logOutputStream == null) return false;
            String line = state + "," + txId + ",,,,,\n";
            logOutputStream.write(line.getBytes(StandardCharsets.UTF_8));
            logOutputStream.flush();
            logOutputStream.getFD().sync(); // Durable flush to disk
            return true;
        } catch (IOException e) {
            System.err.println("[" + serverId + "] Failed to flush intent resolution: " + e.getMessage());
            return false;
        }
    }

    public Account createAccount(String accountId, String ownerName, long initialBalancePaise) {
        Account newAcc = new Account(accountId, ownerName, initialBalancePaise);
        Account existing = accounts.putIfAbsent(accountId, newAcc);
        if (existing != null) {
            throw new IllegalStateException("Account " + accountId + " already exists");
        }
        return newAcc;
    }

    public Account getAccount(String accountId) {
        return accounts.get(accountId);
    }

    public Set<String> getAccountIds() {
        return accounts.keySet();
    }

    public long getBalance(String accountId) {
        Account acc = accounts.get(accountId);
        if (acc == null) throw new NoSuchElementException("Account not found: " + accountId);
        return acc.getCommittedBalance();
    }

    public long deposit(String accountId, long amountPaise) {
        Account acc = accounts.get(accountId);
        if (acc == null) throw new NoSuchElementException("Account not found: " + accountId);
        return acc.deposit(amountPaise);
    }

    public long withdraw(String accountId, long amountPaise) {
        Account acc = accounts.get(accountId);
        if (acc == null) throw new NoSuchElementException("Account not found: " + accountId);
        return acc.withdraw(amountPaise);
    }

    /**
     * Executes atomic intra-server transfer with total lock ordering to prevent deadlocks.
     * Performs direct mutations under the held locks without nested public locking calls.
     */
    public void localTransfer(String srcId, String dstId, long amountPaise) {
        Account src = accounts.get(srcId);
        if (src == null) throw new NoSuchElementException("Source account not found: " + srcId);
        Account dst = accounts.get(dstId);
        if (dst == null) throw new NoSuchElementException("Destination account not found: " + dstId);

        if (srcId.equals(dstId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        Account first = srcId.compareTo(dstId) < 0 ? src : dst;
        Account second = srcId.compareTo(dstId) < 0 ? dst : src;

        first.getLock().lock();
        try {
            second.getLock().lock();
            try {
                src.internalWithdraw(amountPaise);
                dst.internalDeposit(amountPaise);
            } finally {
                second.getLock().unlock();
            }
        } finally {
            first.getLock().unlock();
        }
    }

    /**
     * 2PC PREPARE DEBIT phase: reserves funds on source account.
     * Atomically rolls back any reservation if intent persistence fails.
     */
    public boolean prepareDebit(String txId, String accountId, long amountPaise) {
        Account acc = accounts.get(accountId);
        if (acc == null) return false;

        boolean locked = false;
        boolean reserved = false;
        try {
            locked = acc.getLock().tryLock(3, TimeUnit.SECONDS);
            if (!locked) return false;

            reserved = acc.internalReserveDebit(amountPaise);
            if (!reserved) return false;

            PreparedOperation op = new PreparedOperation(
                    txId, accountId, PreparedOperation.OpType.DEBIT, amountPaise, Instant.now().toString());
            boolean logged = appendIntent("PREPARED", op);
            if (!logged) {
                acc.internalReleaseReservation(amountPaise);
                reserved = false;
                return false;
            }
            preparedOps.put(txId, op);
            return true;
        } catch (InterruptedException e) {
            if (reserved) {
                acc.internalReleaseReservation(amountPaise);
            }
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            if (reserved) {
                acc.internalReleaseReservation(amountPaise);
            }
            preparedOps.remove(txId);
            return false;
        } finally {
            if (locked) {
                acc.getLock().unlock();
            }
        }
    }

    /**
     * 2PC PREPARE CREDIT phase: verifies destination account exists.
     */
    public boolean prepareCredit(String txId, String accountId, long amountPaise) {
        Account acc = accounts.get(accountId);
        if (acc == null) return false;

        PreparedOperation op = new PreparedOperation(
                txId, accountId, PreparedOperation.OpType.CREDIT, amountPaise, Instant.now().toString());
        boolean logged = appendIntent("PREPARED", op);
        if (!logged) {
            return false;
        }
        preparedOps.put(txId, op);
        return true;
    }

    /**
     * 2PC COMMIT phase: applies the prepared debit or credit permanently.
     */
    public boolean commit(String txId) {
        PreparedOperation op = preparedOps.remove(txId);
        if (op == null) return false;

        Account acc = accounts.get(op.accountId());
        if (acc != null) {
            if (op.opType() == PreparedOperation.OpType.DEBIT) {
                acc.commitDebit(op.amountPaise());
            } else {
                acc.commitCredit(op.amountPaise());
            }
        }
        appendResolution("COMMITTED", txId);
        return true;
    }

    /**
     * 2PC ABORT phase: releases reservations on the account.
     */
    public boolean abort(String txId) {
        PreparedOperation op = preparedOps.remove(txId);
        if (op == null) return false;

        Account acc = accounts.get(op.accountId());
        if (acc != null && op.opType() == PreparedOperation.OpType.DEBIT) {
            acc.releaseReservation(op.amountPaise());
        }
        appendResolution("ABORTED", txId);
        return true;
    }

    public PreparedOperation getPreparedOp(String txId) {
        return preparedOps.get(txId);
    }

    public Map<String, PreparedOperation> getAllPreparedOps() {
        return Collections.unmodifiableMap(preparedOps);
    }

    public synchronized void close() {
        try {
            if (logOutputStream != null) {
                logOutputStream.flush();
                logOutputStream.close();
            }
        } catch (IOException ignored) {}
    }

    public void clearAllForTesting() {
        accounts.clear();
        preparedOps.clear();
        close();
        if (intentLogFile.exists()) {
            intentLogFile.delete();
        }
        initLogFile();
    }
}
