package com.upc.computer.assistant.v2.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Separate maintenance principal. MES sessions and assistant run tokens confer no rights here. */
public final class GraphExportAccess {
    private final byte[] credential;
    private final Set<String> allowedOrderIds;

    public GraphExportAccess(String key, Set<String> allowedOrderIds) {
        if (key == null || key.length() < 32 || key.length() > 256 || !key.matches("[A-Za-z0-9_\\-]{32,256}"))
            throw new IllegalArgumentException("Graph export requires a private random maintenance credential of 32-256 URL-safe characters");
        if (allowedOrderIds == null || allowedOrderIds.isEmpty() || allowedOrderIds.stream().anyMatch(id -> !validId(id)))
            throw new IllegalArgumentException("Graph export requires explicitly configured positive order primary keys");
        this.credential = key.getBytes(StandardCharsets.UTF_8);
        this.allowedOrderIds = Set.copyOf(allowedOrderIds);
    }
    public long requireOrder(String authorization, String orderPk) {
        requireCredential(authorization);
        if (!validId(orderPk)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid orderPk");
        if (!allowedOrderIds.contains(orderPk)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Order is outside maintenance scope");
        return Long.parseLong(orderPk);
    }
    public void requireCredential(String authorization) {
        if (authorization == null || authorization.length() > 263 || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(credential, authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Maintenance authentication required");
    }
    private static boolean validId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(id) > 0; } catch (NumberFormatException ignored) { return false; }
    }
}
