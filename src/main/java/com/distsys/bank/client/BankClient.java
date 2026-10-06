package com.distsys.bank.client;

import com.distsys.bank.common.Constants;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

/**
 * Interactive Command-Line Client for the Distributed Bank Transaction System.
 * Connects directly to the BankGateway.
 */
public class BankClient {
    private final String host;
    private final int port;

    public BankClient() {
        this(Constants.HOST, Constants.GATEWAY_PORT);
    }

    public BankClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Sends a command line to the Gateway over TCP and reads the complete response.
     */
    public String executeCommand(String commandLine) throws IOException {
        try (Socket socket = new Socket(host, port);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            socket.setSoTimeout(Constants.SOCKET_TIMEOUT_MS);
            writer.println(commandLine);

            String firstLine = reader.readLine();
            if (firstLine == null) return "ERROR: Empty response from gateway";

            if (firstLine.startsWith("HISTORY:")) {
                StringBuilder sb = new StringBuilder(firstLine).append("\n");
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("END_OF_HISTORY".equals(line)) break;
                    sb.append(line).append("\n");
                }
                return sb.toString().trim();
            }

            return firstLine;
        }
    }

    public void startInteractiveRepl() {
        System.out.println("=========================================================");
        System.out.println("  Distributed Bank Transaction System - Client CLI");
        System.out.println("  Connected to Gateway at " + host + ":" + port);
        System.out.println("  Commands:");
        System.out.println("    CREATE <accId> <owner> <amount>");
        System.out.println("    DEPOSIT <accId> <amount>");
        System.out.println("    WITHDRAW <accId> <amount>");
        System.out.println("    BALANCE <accId>");
        System.out.println("    TRANSFER <sourceAcc> <destAcc> <amount>");
        System.out.println("    HISTORY [accId]");
        System.out.println("    EXIT");
        System.out.println("=========================================================");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("bank-client> ");
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                if ("EXIT".equalsIgnoreCase(line) || "QUIT".equalsIgnoreCase(line)) {
                    System.out.println("Exiting client.");
                    break;
                }

                try {
                    String response = executeCommand(line);
                    System.out.println(response);
                } catch (IOException e) {
                    System.err.println("Connection error: " + e.getMessage());
                }
            }
        }
    }

    public static void main(String[] args) {
        String host = Constants.HOST;
        int port = Constants.GATEWAY_PORT;

        if (args.length > 0) host = args[0];
        if (args.length > 1) port = Integer.parseInt(args[1]);

        BankClient client = new BankClient(host, port);
        client.startInteractiveRepl();
    }
}
