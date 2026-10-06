package com.distsys.bank.common;

/**
 * System-wide constants for networking, ports, and default configurations.
 */
public final class Constants {
    private Constants() {}

    public static final String HOST = "localhost";
    public static final int GATEWAY_PORT = 8000;
    public static final int SERVER_1_PORT = 8001;
    public static final int SERVER_2_PORT = 8002;
    public static final int SERVER_3_PORT = 8003;

    public static final String SERVER_1_ID = "Server1";
    public static final String SERVER_2_ID = "Server2";
    public static final String SERVER_3_ID = "Server3";

    public static final int SOCKET_TIMEOUT_MS = 3000;
    public static final int LOCK_TIMEOUT_SECONDS = 3;
    public static final int THREAD_POOL_SIZE = 32;

    public static final String LOG_FILE_PATH = "transactions.log";
}
