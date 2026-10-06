package com.distsys.bank.model;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe Bank Account entity.
 * Uses fine-grained ReentrantLock per account to allow maximum concurrency.
 * Monetary balances are strictly stored as long integer paise.
 */
public class Account {
    private final String accountId;
    private final String ownerName;
    private long balance;             // Committed balance in paise
    private long reservedDebits;      // Balance reserved by active 2PC transactions in paise
    private final ReentrantLock lock;

    public Account(String accountId, String ownerName, long initialBalancePaise) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be empty");
        }
        if (initialBalancePaise < 0) {
            throw new IllegalArgumentException("Initial balance cannot be negative");
        }
        this.accountId = accountId.trim();
        this.ownerName = ownerName != null ? ownerName.trim() : "Unknown";
        this.balance = initialBalancePaise;
        this.reservedDebits = 0L;
        this.lock = new ReentrantLock(true); // Fair lock
    }

    public String getAccountId() {
        return accountId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public ReentrantLock getLock() {
        return lock;
    }

    public long getCommittedBalance() {
        lock.lock();
        try {
            return balance;
        } finally {
            lock.unlock();
        }
    }

    public long getAvailableBalance() {
        lock.lock();
        try {
            return balance - reservedDebits;
        } finally {
            lock.unlock();
        }
    }

    public long deposit(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        lock.lock();
        try {
            balance += amountPaise;
            return balance;
        } finally {
            lock.unlock();
        }
    }

    public long withdraw(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        lock.lock();
        try {
            if ((balance - reservedDebits) < amountPaise) {
                throw new IllegalStateException("Insufficient funds");
            }
            balance -= amountPaise;
            return balance;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reserves funds during 2PC PREPARE_DEBIT phase.
     */
    public boolean reserveDebit(long amountPaise) {
        lock.lock();
        try {
            if ((balance - reservedDebits) < amountPaise) {
                return false;
            }
            reservedDebits += amountPaise;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases reservation during 2PC ABORT phase.
     */
    public void releaseReservation(long amountPaise) {
        lock.lock();
        try {
            reservedDebits = Math.max(0L, reservedDebits - amountPaise);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Finalizes debit during 2PC COMMIT phase.
     */
    public void commitDebit(long amountPaise) {
        lock.lock();
        try {
            reservedDebits = Math.max(0L, reservedDebits - amountPaise);
            balance -= amountPaise;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Finalizes credit during 2PC COMMIT phase.
     */
    public void commitCredit(long amountPaise) {
        lock.lock();
        try {
            balance += amountPaise;
        } finally {
            lock.unlock();
        }
    }
}
