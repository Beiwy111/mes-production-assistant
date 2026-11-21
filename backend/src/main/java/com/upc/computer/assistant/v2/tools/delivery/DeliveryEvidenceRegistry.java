package com.upc.computer.assistant.v2.tools.delivery;

import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Bounded, process-local evidence references. These are MES-session references, not Dify credentials. */
public final class DeliveryEvidenceRegistry {
    public static final String VERSION = "delivery-readonly-1";
    public record Scope(String ref, long userId, String orderId, LocalDate targetDate, String targetKind, Instant expires) { }
    public record Query(String ref, String scopeRef, String kind, String fingerprint) { }
    private final Clock clock;
    private final Map<String,Scope> scopes = new HashMap<>();
    private final Map<String,Query> queries = new HashMap<>();
    public DeliveryEvidenceRegistry(Clock clock) { this.clock = clock; }
    public synchronized Scope create(long userId, String orderId, LocalDate targetDate, String targetKind) {
        cleanup();
        if (scopes.size() >= 256) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Scope capacity reached");
        var scope = new Scope(UUID.randomUUID().toString(), userId, orderId, targetDate, targetKind, clock.instant().plusSeconds(600));
        scopes.put(scope.ref(), scope); return scope;
    }
    public synchronized Scope requireScope(long userId, String orderId, String ref) {
        cleanup(); var scope = scopes.get(ref);
        if (scope == null || scope.userId() != userId || !scope.orderId().equals(orderId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scope unavailable");
        return scope;
    }
    public synchronized String register(Scope scope, String kind, String fingerprint) {
        cleanup();
        if (!scopes.containsKey(scope.ref())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Scope expired");
        if (queries.size() >= 4096) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Evidence capacity reached");
        String ref = UUID.randomUUID().toString(); queries.put(ref, new Query(ref, scope.ref(), kind, fingerprint)); return ref;
    }
    public synchronized Query requireQuery(Scope scope, String ref, String kind) {
        cleanup(); var query = queries.get(ref);
        if (query == null || !query.scopeRef().equals(scope.ref()) || (kind != null && !kind.equals(query.kind()))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence unavailable");
        return query;
    }
    private void cleanup() {
        scopes.values().removeIf(s -> !s.expires().isAfter(clock.instant()));
        queries.values().removeIf(q -> !scopes.containsKey(q.scopeRef()));
    }
    public static String fingerprint(Map<String,? extends List<? extends Map<String,String>>> tables) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            update(digest, VERSION);
            for (String table : new TreeSet<>(tables.keySet())) {
                update(digest, table);
                List<String> records = new ArrayList<>();
                for (var row : tables.get(table)) {
                    StringBuilder serialized = new StringBuilder();
                    for (String key : new TreeSet<>(row.keySet())) {
                        String value = row.get(key);
                        serialized.append(key.length()).append(':').append(key).append(':').append(value == null ? -1 : value.length()).append(':').append(value);
                    }
                    records.add(serialized.toString());
                }
                Collections.sort(records); for (String record : records) update(digest, record);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void update(MessageDigest digest, String value) {
        digest.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
    }
}
