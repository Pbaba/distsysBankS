package com.distsys.bank.server;

/**
 * Deterministic failure simulation hooks for BankServer testing.
 * Satisfies Section 6 of the assignment specification.
 */
public enum FailMode {
    NONE,
    CRASH_BEFORE_TRANSACTION, // Closes connection before processing command
    CRASH_DURING_PREPARE,     // Fails / drops connection during 2PC PREPARE
    CRASH_BEFORE_COMMIT,      // Drops connection upon receiving COMMIT, before applying local commit
    CRASH_DURING_COMMIT       // Applies local commit, but drops connection before sending ACK (simulates lost ACK)
}
