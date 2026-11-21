package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Local signed scope records, not cached business facts or resumable workflow checkpoints. */
public final class ConversationScopeStore {
    private static final int MAX_BYTES=65536,MAX_ENTRIES=512;
    private static final Duration RETENTION=Duration.ofDays(7);
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path directory;
    private final String namespace;
    private final Clock clock;
    private final byte[] key;
    private ConversationScopeStore() {directory=null;namespace="disabled";clock=Clock.systemUTC();key=new byte[0];}
    public static ConversationScopeStore disabled(){return new ConversationScopeStore();}
    public ConversationScopeStore(Path root,String namespace,Clock clock,String secret) throws java.io.IOException {
        if(!Set.of("runtime","gateway","audit").contains(namespace)||secret==null||secret.length()<24)throw new IllegalArgumentException("Invalid scope store configuration");
        Path base=root.toAbsolutePath().normalize();this.directory=base.resolve(namespace).normalize();
        if(!directory.startsWith(base)||Files.isSymbolicLink(base)||Files.isSymbolicLink(directory))throw new IllegalArgumentException("Invalid scope store path");
        Files.createDirectories(directory);this.namespace=namespace;this.clock=clock;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }
    public synchronized boolean save(String ref,long owner,Map<String,Object> payload) {
        if(directory==null||!validRef(ref)||owner<=0||payload==null)return false;
        Path temporary=null;
        try {
            var body=json.createObjectNode().put("version",1).put("namespace",namespace).put("ref",ref).put("owner",owner)
                .put("expiresAt",clock.instant().plus(RETENTION).toString());
            body.set("payload",json.valueToTree(payload));
            var envelope=json.createObjectNode();envelope.set("body",body);envelope.put("signature",sign(body));
            byte[] bytes=json.writeValueAsBytes(envelope);if(bytes.length>maximumBytes())return false;
            Path target=path(ref);if(Files.isSymbolicLink(target))return false;
            // An audit may update many times in one run; capacity changes only on a new file.
            if(!namespace.equals("audit")||!Files.exists(target,LinkOption.NOFOLLOW_LINKS))prune();
            temporary=Files.createTempFile(directory,"scope-",".tmp");
            Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return true;
        }catch(Exception unavailable){return false;}
        finally {if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(java.io.IOException ignored){}}
    }
    public synchronized Map<String,Object> load(String ref,long owner) {
        if(directory==null||!validRef(ref)||owner<=0)return null;
        try {
            JsonNode body=verified(path(ref));
            if(body==null||!body.path("ref").asText().equals(ref)||body.path("owner").asLong()!=owner)return null;
            return json.convertValue(body.path("payload"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        }catch(Exception unavailable){return null;}
    }
    private JsonNode verified(Path file) throws Exception {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>maximumBytes())return null;
        JsonNode envelope=json.readTree(Files.readAllBytes(file)),body=envelope.path("body");
        if(envelope.size()!=2||body.size()!=6||body.path("version").asInt()!=1||!namespace.equals(body.path("namespace").asText())
            ||!body.path("payload").isObject()||!Instant.parse(body.path("expiresAt").asText()).isAfter(clock.instant()))return null;
        if(!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII),envelope.path("signature").asText().getBytes(StandardCharsets.US_ASCII)))return null;
        return body;
    }
    private int maximumBytes(){return namespace.equals("audit")?262144:MAX_BYTES;}
    private String sign(JsonNode body) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(json.writeValueAsBytes(body)));
    }
    private void prune() throws Exception {
        List<Path> retained=new ArrayList<>();
        try(var entries=Files.newDirectoryStream(directory,"*.json")) {
            for(Path file:entries) {
                String name=file.getFileName().toString();
                if(!validRef(name.substring(0,name.length()-5))||!file.toAbsolutePath().normalize().startsWith(directory)||Files.isSymbolicLink(file))continue;
                boolean valid;try{valid=verified(file)!=null;}catch(Exception invalid){valid=false;}
                if(!valid)Files.deleteIfExists(file);else retained.add(file);
            }
        }
        retained.sort(Comparator.comparing(file->{try{return Files.getLastModifiedTime(file).toInstant();}catch(Exception ignored){return Instant.MIN;}}));
        for(int i=0;i<=retained.size()-MAX_ENTRIES;i++)Files.deleteIfExists(retained.get(i));
    }
    private Path path(String ref) {Path file=directory.resolve(ref+".json").normalize();if(!file.startsWith(directory))throw new IllegalArgumentException("Invalid reference");return file;}
    private static boolean validRef(String ref){return ref!=null&&ref.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
}
