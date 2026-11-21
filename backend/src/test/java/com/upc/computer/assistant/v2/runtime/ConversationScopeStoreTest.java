package com.upc.computer.assistant.v2.runtime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversationScopeStoreTest {
 @TempDir Path dir;
 final Clock clock=Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"),ZoneOffset.UTC);
 final String secret="unit-test-signing-key-not-a-real-secret";
 @Test void survivesRestartButRejectsOtherOwnerTamperingAndExpiry() throws Exception {
  String id=UUID.randomUUID().toString();var store=new ConversationScopeStore(dir,"runtime",clock,secret);
  assertTrue(store.save(id,42,Map.of("scope",Map.of("keyword","PCB"))));
  var restarted=new ConversationScopeStore(dir,"runtime",clock,secret);
  assertEquals(Map.of("scope",Map.of("keyword","PCB")),restarted.load(id,42));
  assertNull(restarted.load(id,43));
  assertNull(new ConversationScopeStore(dir,"runtime",Clock.offset(clock,Duration.ofDays(8)),secret).load(id,42));
  Path file=dir.resolve("runtime").resolve(id+".json");Files.writeString(file,Files.readString(file).replace("PCB","HACK"));
  assertNull(restarted.load(id,42));
 }
 @Test void storeNamesAndReferencesCannotEscapeOrCrossTrustBoundaries() throws Exception {
  String id=UUID.randomUUID().toString();var runtime=new ConversationScopeStore(dir,"runtime",clock,secret);
  var gateway=new ConversationScopeStore(dir,"gateway",clock,secret);
  assertTrue(runtime.save(id,42,Map.of("scope",Map.of())));
  Files.copy(dir.resolve("runtime").resolve(id+".json"),dir.resolve("gateway").resolve(id+".json"));
  assertNull(gateway.load(id,42));assertNull(runtime.load("../other",42));
  assertFalse(runtime.save("../other",42,Map.of()));
  assertFalse(runtime.save(UUID.randomUUID().toString(),42,Map.of("tooLarge","x".repeat(70000))));
 }
 @Test void expiredFilesAreRemovedOnlyInsideDedicatedDirectory() throws Exception {
  String id=UUID.randomUUID().toString();var store=new ConversationScopeStore(dir,"runtime",clock,secret);store.save(id,42,Map.of());
  Files.writeString(dir.resolve("unrelated.txt"),"keep");
  var later=new ConversationScopeStore(dir,"runtime",Clock.offset(clock,Duration.ofDays(8)),secret);
  assertTrue(later.save(UUID.randomUUID().toString(),42,Map.of("new",true)));
  assertFalse(Files.exists(dir.resolve("runtime").resolve(id+".json")));assertTrue(Files.exists(dir.resolve("unrelated.txt")));
 }
}
