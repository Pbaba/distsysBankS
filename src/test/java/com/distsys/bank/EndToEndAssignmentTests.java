package com.distsys.bank;

import com.distsys.bank.client.BankClient;
import com.distsys.bank.gateway.BankGateway;
import com.distsys.bank.gateway.RoutingTable;
import com.distsys.bank.gateway.TransactionLogger;
import com.distsys.bank.server.BankServer;
import com.distsys.bank.server.FailMode;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test suite verifying all 8 required test cases from
 * the assignment specification (PDF Section 9).
 */
public class EndToEndAssignmentTests {

    private static final int GW_PORT = 9100;
    private static final int S1_PORT = 9101;
    private static final int S2_PORT = 9102;
    private static final int S3_PORT = 9103;

    private BankServer server1;
    private BankServer server2;
    private BankServer server3;
    private BankGateway gateway;
    private TransactionLogger logger;
    private BankClient client;

    @BeforeEach
    void setUp() throws IOException {
        File testLog = new File("test_transactions.log");
        if (testLog.exists()) testLog.delete();
        logger = new TransactionLogger(testLog);

        RoutingTable routingTable = new RoutingTable(S1_PORT, S2_PORT, S3_PORT);
        gateway = new BankGateway(GW_PORT, routingTable, logger);

        server1 = new BankServer("Server1", S1_PORT);
        server2 = new BankServer("Server2", S2_PORT);
        server3 = new BankServer("Server3", S3_PORT);

        server1.getRepository().clearAllForTesting();
        server2.getRepository().clearAllForTesting();
        server3.getRepository().clearAllForTesting();

        server1.start();
        server2.start();
        server3.start();
        gateway.start();

        client = new BankClient("localhost", GW_PORT);

        // Seed default accounts per assignment specifications
        client.executeCommand("CREATE A101 Ram 10000");       // ₹10,000 on Server 1
        client.executeCommand("CREATE A102 Sita 10000");      // ₹10,000 on Server 1
        client.executeCommand("CREATE A201 Lakshman 20000");  // ₹20,000 on Server 2
        client.executeCommand("CREATE A202 Urmila 20000");    // ₹20,000 on Server 2
        client.executeCommand("CREATE A301 Bharat 15000");    // ₹15,000 on Server 3
        client.executeCommand("CREATE A302 Mandavi 15000");   // ₹15,000 on Server 3
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.stop();
        if (server1 != null) server1.stop();
        if (server2 != null) server2.stop();
        if (server3 != null) server3.stop();
    }

    /**
     * Test Case 1: Deposit
     * Input: A101 = ₹10,000; deposit ₹2,000
     * Expected Result: A101 = ₹12,000
     */
    @Test
    void testCase1_Deposit() throws IOException {
        String resp = client.executeCommand("DEPOSIT A101 2000");
        assertTrue(resp.startsWith("SUCCESS"), "Deposit should succeed: " + resp);

        String balResp = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = 12000.00", balResp);
    }

    /**
     * Test Case 2: Withdrawal
     * Input: A101 = ₹10,000; withdraw ₹3,000
     * Expected Result: A101 = ₹7,000
     */
    @Test
    void testCase2_Withdrawal() throws IOException {
        String resp = client.executeCommand("WITHDRAW A101 3000");
        assertTrue(resp.startsWith("SUCCESS"), "Withdrawal should succeed: " + resp);

        String balResp = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = 7000.00", balResp);
    }

    /**
     * Test Case 3: Insufficient Balance
     * Input: A101 = ₹10,000; withdraw ₹15,000
     * Expected Result: FAILED, balance unchanged at ₹10,000
     */
    @Test
    void testCase3_InsufficientBalance() throws IOException {
        String resp = client.executeCommand("WITHDRAW A101 15000");
        assertTrue(resp.startsWith("FAILED"), "Overdraft withdrawal must fail: " + resp);

        String balResp = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = 10000.00", balResp);
    }

    /**
     * Test Case 4: Successful Distributed Transfer
     * Input: A101 = ₹10,000 (S1); A201 = ₹20,000 (S2); transfer ₹5,000
     * Expected Result: A101 = ₹5,000; A201 = ₹25,000
     */
    @Test
    void testCase4_SuccessfulDistributedTransfer() throws IOException {
        String resp = client.executeCommand("TRANSFER A101 A201 5000");
        assertTrue(resp.startsWith("SUCCESS"), "Distributed transfer should succeed: " + resp);

        String balA101 = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = 5000.00", balA101);

        String balA201 = client.executeCommand("BALANCE A201");
        assertEquals("SUCCESS: A201 balance = 25000.00", balA201);
    }

    /**
     * Test Case 5: Concurrent Transfers
     * Input: Two or more simultaneous transfers involving the same account
     * Expected Result: Correct final balance (no lost updates)
     */
    @Test
    void testCase5_ConcurrentTransfers() throws Exception {
        int threads = 10;
        int transfersPerThread = 20;
        long transferAmount = 10; // ₹10 each transfer

        // Initial A101 = 10000. Each transfer moves 10 from A101 to A201.
        // Total deducted = 10 * 20 * 10 = 2000.
        // Expected final: A101 = 8000, A201 = 22000.
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads * transfersPerThread);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                BankClient threadClient = new BankClient("localhost", GW_PORT);
                for (int j = 0; j < transfersPerThread; j++) {
                    try {
                        threadClient.executeCommand("TRANSFER A101 A201 " + transferAmount);
                    } catch (IOException ignored) {}
                    finally {
                        latch.countDown();
                    }
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent transfers timed out");
        executor.shutdownNow();

        long expectedA101 = 10000 - (threads * transfersPerThread * transferAmount);
        long expectedA201 = 20000 + (threads * transfersPerThread * transferAmount);

        String balA101 = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = " + expectedA101 + ".00", balA101);

        String balA201 = client.executeCommand("BALANCE A201");
        assertEquals("SUCCESS: A201 balance = " + expectedA201 + ".00", balA201);
    }

    /**
     * Test Case 6: Concurrent Withdrawal
     * Input: Initial A101 = ₹10,000; T1 withdraws ₹7,000; T2 withdraws ₹7,000
     * Expected Result: Only one transaction should succeed; final balance = ₹3,000
     */
    @Test
    void testCase6_ConcurrentWithdrawal() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        List<String> responses = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                BankClient threadClient = new BankClient("localhost", GW_PORT);
                try {
                    startLatch.await();
                    String resp = threadClient.executeCommand("WITHDRAW A101 7000");
                    responses.add(resp);
                    if (resp.startsWith("SUCCESS")) {
                        successes.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (Exception ignored) {}
                finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release both threads at the exact same instant
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertEquals(1, successes.get(), "Exactly one concurrent withdrawal must succeed");
        assertEquals(1, failures.get(), "Exactly one concurrent withdrawal must fail");

        String balResp = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = 3000.00", balResp);
    }

    /**
     * Test Case 7: Server Failure
     * Input: Terminate / crash one server during a transaction
     * Expected Result: Correct failure/recovery behavior; money is not lost or debited
     */
    @Test
    void testCase7_ServerFailure_CrashDuringPrepare() throws IOException {
        // Set deterministic failure hook on destination server (Server 2) during prepare
        server2.setFailMode(FailMode.CRASH_DURING_PREPARE);

        String resp = client.executeCommand("TRANSFER A101 A201 5000");
        assertTrue(resp.startsWith("FAILED"), "Transfer must fail when participant crashes during prepare: " + resp);

        // Crucial atomicity check: A101 balance must be untouched at ₹10,000
        String balA101 = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = 10000.00", balA101, "Source balance must not be debited");

        // Destination balance must be untouched at ₹20,000
        String balA201 = client.executeCommand("BALANCE A201");
        assertEquals("SUCCESS: A201 balance = 20000.00", balA201, "Destination balance must be untouched");
    }

    @Test
    void testCase7_ServerFailure_ServerDownBeforeTransaction() throws IOException {
        // Stop Server 2 completely (simulating process termination)
        server2.stop();

        String resp = client.executeCommand("TRANSFER A101 A201 5000");
        assertTrue(resp.startsWith("FAILED"), "Transfer must fail when server is offline: " + resp);

        // Verify source balance remains untouched
        String balA101 = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = 10000.00", balA101);
    }

    /**
     * Test Case 8: Invalid Account
     * Input: TRANSFER A101 A999 5000
     * Expected Result: FAILED: Destination account not found
     */
    @Test
    void testCase8_InvalidAccount() throws IOException {
        String resp = client.executeCommand("TRANSFER A101 A999 5000");
        assertTrue(resp.startsWith("FAILED"), "Transfer to invalid account must fail: " + resp);
        assertTrue(resp.contains("Destination account not found"), "Error message must state destination not found: " + resp);

        // A101 balance must be unchanged
        String balA101 = client.executeCommand("BALANCE A101");
        assertEquals("SUCCESS: A101 balance = 10000.00", balA101);
    }
}
