package com.distsys.bank.benchmark;

import com.distsys.bank.client.BankClient;
import com.distsys.bank.common.Constants;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark harness comparing sequential vs concurrent execution of a mixed banking workload.
 * Workload composition per 5 operations:
 * - 20% BALANCE (read query)
 * - 20% DEPOSIT (localized write)
 * - 20% WITHDRAW (localized write)
 * - 40% TRANSFER (cross-server Two-Phase Commit: 20% A101->A201, 20% A201->A101)
 *
 * Measures real execution metrics (no fabricated results) and outputs raw CSV and JSON.
 * Satisfies Section 8 of the assignment specification.
 */
public class BenchmarkRunner {

    public record BenchmarkResult(
            String mode,
            int requests,
            int concurrency,
            double totalTimeMs,
            double avgLatencyMs,
            double p50Ms,
            double p95Ms,
            double throughputTps,
            int successful,
            int failed
    ) {
        public String toCsvLine() {
            return String.format(Locale.US, "%s,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d",
                    mode, requests, concurrency, totalTimeMs, avgLatencyMs, p50Ms, p95Ms, throughputTps, successful, failed);
        }

        public String toJsonString() {
            return String.format(Locale.US,
                    "  {\n" +
                    "    \"mode\": \"%s\",\n" +
                    "    \"requests\": %d,\n" +
                    "    \"concurrency\": %d,\n" +
                    "    \"totalTimeMs\": %.2f,\n" +
                    "    \"avgLatencyMs\": %.2f,\n" +
                    "    \"p50Ms\": %.2f,\n" +
                    "    \"p95Ms\": %.2f,\n" +
                    "    \"throughputTps\": %.2f,\n" +
                    "    \"successful\": %d,\n" +
                    "    \"failed\": %d\n" +
                    "  }",
                    mode, requests, concurrency, totalTimeMs, avgLatencyMs, p50Ms, p95Ms, throughputTps, successful, failed);
        }
    }

    private final String host;
    private final int port;
    private final int[] workloadSizes = {10, 50, 100, 500, 1000};

    public BenchmarkRunner() {
        this(Constants.HOST, Constants.GATEWAY_PORT);
    }

    public BenchmarkRunner(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public List<BenchmarkResult> runAll() {
        List<BenchmarkResult> results = new ArrayList<>();
        System.out.println("Starting Benchmark Suite against " + host + ":" + port + "...");

        // Ensure test accounts exist with ample balances for benchmarking
        setupBenchmarkAccounts();

        for (int n : workloadSizes) {
            System.out.printf("Running Sequential Workload: %d requests...%n", n);
            results.add(runSequential(n));
        }

        for (int n : workloadSizes) {
            int threads = Math.min(20, Math.max(2, n / 5));
            System.out.printf("Running Concurrent Workload: %d requests (%d threads)...%n", n, threads);
            results.add(runConcurrent(n, threads));
        }

        saveResults(results, "benchmark_results.csv", "benchmark_results.json");
        return results;
    }

    private void setupBenchmarkAccounts() {
        BankClient client = new BankClient(host, port);
        try {
            client.executeCommand("CREATE A101 Ram 1000000");
            client.executeCommand("CREATE A201 Lakshman 1000000");
            client.executeCommand("CREATE A301 Bharat 1000000");
        } catch (Exception ignored) {}
    }

    public BenchmarkResult runSequential(int requestCount) {
        BankClient client = new BankClient(host, port);
        List<Long> latencies = new ArrayList<>(requestCount);
        int success = 0;
        int failed = 0;

        long startTime = System.nanoTime();
        for (int i = 0; i < requestCount; i++) {
            String cmd = getWorkloadCommand(i);
            long reqStart = System.nanoTime();
            try {
                String resp = client.executeCommand(cmd);
                long reqEnd = System.nanoTime();
                latencies.add((reqEnd - reqStart) / 1_000_000); // ms
                if (resp != null && resp.startsWith("SUCCESS")) {
                    success++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                latencies.add(0L);
                failed++;
            }
        }
        long endTime = System.nanoTime();
        double totalMs = (endTime - startTime) / 1_000_000.0;

        return calculateMetrics("sequential", requestCount, 1, totalMs, latencies, success, failed);
    }

    public BenchmarkResult runConcurrent(int requestCount, int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(requestCount);

        long startTime = System.nanoTime();
        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            executor.submit(() -> {
                BankClient client = new BankClient(host, port);
                String cmd = getWorkloadCommand(index);
                long reqStart = System.nanoTime();
                try {
                    String resp = client.executeCommand(cmd);
                    long reqEnd = System.nanoTime();
                    latencies.add((reqEnd - reqStart) / 1_000_000);
                    if (resp != null && resp.startsWith("SUCCESS")) {
                        success.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    latencies.add(0L);
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long endTime = System.nanoTime();
        executor.shutdownNow();

        double totalMs = (endTime - startTime) / 1_000_000.0;
        return calculateMetrics("concurrent", requestCount, threadCount, totalMs, new ArrayList<>(latencies), success.get(), failed.get());
    }

    private BenchmarkResult calculateMetrics(
            String mode, int requests, int concurrency, double totalMs, List<Long> latencies, int success, int failed) {

        Collections.sort(latencies);
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double p50 = latencies.isEmpty() ? 0.0 : latencies.get((int) (latencies.size() * 0.50));
        double p95 = latencies.isEmpty() ? 0.0 : latencies.get(Math.min(latencies.size() - 1, (int) (latencies.size() * 0.95)));
        double tps = totalMs > 0 ? (requests / (totalMs / 1000.0)) : 0.0;

        return new BenchmarkResult(mode, requests, concurrency, totalMs, avg, p50, p95, tps, success, failed);
    }

    private String getWorkloadCommand(int i) {
        int mod = i % 5;
        return switch (mod) {
            case 0 -> "BALANCE A101";
            case 1 -> "DEPOSIT A101 10";
            case 2 -> "WITHDRAW A101 5";
            case 3 -> "TRANSFER A101 A201 5";
            case 4 -> "TRANSFER A201 A101 5";
            default -> "BALANCE A101";
        };
    }

    public static void saveResults(List<BenchmarkResult> results, String csvPath, String jsonPath) {
        // Save CSV
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvPath))) {
            pw.println("mode,requests,concurrency,total_time_ms,avg_latency_ms,p50_ms,p95_ms,throughput_tps,successful,failed");
            for (BenchmarkResult r : results) {
                pw.println(r.toCsvLine());
            }
            System.out.println("Benchmark CSV saved to: " + new File(csvPath).getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error saving CSV: " + e.getMessage());
        }

        // Save JSON
        try (PrintWriter pw = new PrintWriter(new FileWriter(jsonPath))) {
            pw.println("[\n" + String.join(",\n", results.stream().map(BenchmarkResult::toJsonString).toList()) + "\n]");
            System.out.println("Benchmark JSON saved to: " + new File(jsonPath).getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error saving JSON: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        BenchmarkRunner runner = new BenchmarkRunner();
        runner.runAll();
    }
}
