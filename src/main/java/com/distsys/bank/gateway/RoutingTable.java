package com.distsys.bank.gateway;

import com.distsys.bank.common.Constants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic prefix-based routing table.
 * Maps account ID prefixes to corresponding BankServer endpoints.
 * A1xx -> Server 1 (8001)
 * A2xx -> Server 2 (8002)
 * A3xx -> Server 3 (8003)
 */
public class RoutingTable {
    public record ServerEndpoint(String serverId, String host, int port) {}

    private final Map<String, ServerEndpoint> prefixMap = new HashMap<>();

    public RoutingTable() {
        this(Constants.SERVER_1_PORT, Constants.SERVER_2_PORT, Constants.SERVER_3_PORT);
    }

    public RoutingTable(int port1, int port2, int port3) {
        prefixMap.put("A1", new ServerEndpoint(Constants.SERVER_1_ID, Constants.HOST, port1));
        prefixMap.put("A2", new ServerEndpoint(Constants.SERVER_2_ID, Constants.HOST, port2));
        prefixMap.put("A3", new ServerEndpoint(Constants.SERVER_3_ID, Constants.HOST, port3));
    }

    public ServerEndpoint getEndpointForAccount(String accountId) {
        if (accountId == null || accountId.trim().length() < 2) {
            return null;
        }
        String prefix = accountId.trim().substring(0, 2).toUpperCase();
        return prefixMap.get(prefix);
    }

    public Map<String, ServerEndpoint> getAllEndpoints() {
        return Collections.unmodifiableMap(prefixMap);
    }
}
