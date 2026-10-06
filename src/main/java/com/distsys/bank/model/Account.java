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

    /**
     * Internal deposit without acquiring lock. Caller must hold this account's lock.
     */
    public void internalDeposit(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        balance += amountPaise;
    }

    /**
     * Internal withdraw without acquiring lock. Caller must hold this account's lock.
     */
    public void internalWithdraw(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        if ((balance - reservedDebits) < amountPaise) {
            throw new IllegalStateException("Insufficient funds");
        }
        balance -= amountPaise;
    }

    /**
     * Internal reservation without acquiring lock. Caller must hold this account's lock.
     */
    public boolean internalReserveDebit(long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Reserve amount must be positive");
        }
        if ((balance - reservedDebits) < amountPaise) {
            return false;
        }
        reservedDebits += amountPaise;
        return true;
    }

    /**
     * Internal release reservation without acquiring lock. Caller must hold this account's lock.
     */
    public void internalReleaseReservation(long amountPaise) {
        reservedDebits = Math.max(0L, reservedDebits - amountPaise);
    }

    public long deposit(long amountPaise) {
        lock.lock();
        try {
            internalDeposit(amountPaise);
            return balance;
        } finally {
            lock.unlock();
        }
    }

    public long withdraw(long amountPaise) {
        lock.lock();
        try {
            internalWithdraw(amountPaise);
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
            return internalReserveDebit(amountPaise);
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
            internalReleaseReservation(amountPaise);
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
            internalDeposit(amountPaise);
        } finally {
            lock.unlock();
        }
    }
}
