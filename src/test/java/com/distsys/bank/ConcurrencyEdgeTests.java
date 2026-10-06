package com.distsys.bank;

import com.distsys.bank.client.BankClient;
import com.distsys.bank.gateway.BankGateway;
import com.distsys.bank.gateway.RoutingTable;
import com.distsys.bank.gateway.TransactionLogger;
import com.distsys.bank.server.BankServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency edge cases and system invariance tests.
 */
public class ConcurrencyEdgeTests {

    private static final int GW_PORT = 9200;
    private static final int S1_PORT = 9201;
    private static final int S2_PORT = 9202;
    private static final int S3_PORT = 9203;

    private BankServer server1;
    private BankServer server2;
    private BankServer server3;
    private BankGateway gateway;
    private BankClient client;

    @BeforeEach
    void setUp() throws IOException {
        File testLog = new File("test_edge_transactions.log");
        if (testLog.exists()) testLog.delete();
        TransactionLogger logger = new TransactionLogger(testLog);

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

        client.executeCommand("CREATE A101 Alice 50000");
        client.executeCommand("CREATE A201 Bob 50000");
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.stop();
        if (server1 != null) server1.stop();
        if (server2 != null) server2.stop();
        if (server3 != null) server3.stop();
    }

    /**
     * Bidirectional concurrent transfers between two servers.
     * Thread A: A101 -> A201
     * Thread B: A201 -> A101
     * Verifies that timed locks and 2PC coordination prevent distributed deadlocks.
     */
    @Test
    void testBidirectionalConcurrentTransfers_NoDeadlock() throws Exception {
        int rounds = 50;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(rounds * 2);

        // Thread A transfers A101 -> A201
        executor.submit(() -> {
            BankClient c = new BankClient("localhost", GW_PORT);
            for (int i = 0; i < rounds; i++) {
                try {
                    c.executeCommand("TRANSFER A101 A201 10");
                } catch (IOException ignored) {}
                finally {
                    latch.countDown();
                }
            }
        });

        // Thread B transfers A201 -> A101
        executor.submit(() -> {
            BankClient c = new BankClient("localhost", GW_PORT);
            for (int i = 0; i < rounds; i++) {
                try {
                    c.executeCommand("TRANSFER A201 A101 10");
                } catch (IOException ignored) {}
                finally {
                    latch.countDown();
                }
            }
        });

        // Must complete in 15 seconds without deadlocking
        boolean completed = latch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(completed, "Bidirectional concurrent transfers must not deadlock");

        // Verify total conservation of money:
        // Initial sum was 50,000 + 50,000 = 100,000.
        // Final sum must be exactly 100,000.
        String balA101Str = client.executeCommand("BALANCE A101").replace("SUCCESS: A101 balance = ", "");
        String balA201Str = client.executeCommand("BALANCE A201").replace("SUCCESS: A201 balance = ", "");

        double total = Double.parseDouble(balA101Str) + Double.parseDouble(balA201Str);
        assertEquals(100000.0, total, 0.001, "Total money across distributed servers must be conserved");
    }

    /**
     * Duplicate account creation must fail cleanly.
     */
    @Test
    void testDuplicateAccountCreation_Fails() throws IOException {
        String resp = client.executeCommand("CREATE A101 DuplicateRam 10000");
        assertTrue(resp.startsWith("FAILED"), "Duplicate account creation must fail: " + resp);
    }

    /**
     * Negative or invalid amounts must be rejected.
     */
    @Test
    void testNegativeOrInvalidAmounts_Rejected() throws IOException {
        String resp1 = client.executeCommand("DEPOSIT A101 -500");
        assertTrue(resp1.startsWith("FAILED"), "Negative deposit must fail: " + resp1);

        String resp2 = client.executeCommand("WITHDRAW A101 0");
        assertTrue(resp2.startsWith("FAILED"), "Zero withdrawal must fail: " + resp2);

        String resp3 = client.executeCommand("TRANSFER A101 A201 -50");
        assertTrue(resp3.startsWith("FAILED"), "Negative transfer must fail: " + resp3);
    }

    /**
     * Verifies HISTORY command formatting and transaction log persistence.
     */
    @Test
    void testHistoryCommand_ReturnsAuditRecords() throws IOException {
        client.executeCommand("DEPOSIT A101 1000");
        client.executeCommand("TRANSFER A101 A201 500");

        String history = client.executeCommand("HISTORY A101");
        assertTrue(history.contains("HISTORY:"), "History header must be present");
        assertTrue(history.contains("A101 -> A201"), "Cross-server transfer must be in history");
        assertTrue(history.contains("Status: SUCCESS"), "Status must be SUCCESS");
    }
}
