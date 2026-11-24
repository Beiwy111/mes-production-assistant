package com.upc.computer.assistant.v2.runtime;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApprovedSopStoreTest {
    @TempDir Path directory;
    @Test void approvalDomainRoleDateAndRelevanceConstrainEachRead() throws Exception {
        var active=directory.resolve("active.json");
        var date=LocalDate.parse("2026-01-03");
        var user=AssistantReadPolicy.of(2,"INSPECTOR",null,List.of("quality:inspection"));
        assertEquals("NO_MATCHES",ApprovedSopStore.inspect(active,"色斑",user,date).get("status"));
        Files.writeString(active,"""
          {"schema":1,"documents":{"SOP1":{"document_id":"SOP1","title":"光学检查",
           "version":"3","domain":"QUALITY","scope":"FACTORY_WIDE","status":"APPROVED","allow_model_processing":true,
           "keywords":["色斑"],"allowed_roles":["INSPECTOR"],"effective_from":"2026-01-01","valid_to":"2026-01-31",
           "sections":[{"location":"page:2","text":"测试引用","evidence_ref":"sop:SOP1:3:abc:1"}]}}}
          """);
        assertEquals("MATCHES",ApprovedSopStore.inspect(active,"怎样核查色斑",user,date).get("status"));
        assertEquals("NO_MATCHES",ApprovedSopStore.inspect(active,"仓库",user,date).get("status"));
        assertEquals("NO_MATCHES",ApprovedSopStore.inspect(active,"色斑",user,date.plusMonths(1)).get("status"));
        assertEquals("NO_MATCHES",ApprovedSopStore.inspect(active,"色斑",
            AssistantReadPolicy.of(3,"VIEWER",null,List.of("quality:inspection")),date).get("status"));
        assertEquals("NO_MATCHES",ApprovedSopStore.inspect(active,"色斑",
            AssistantReadPolicy.of(4,"INSPECTOR",null,List.of()),date).get("status"));
        Files.writeString(active,"invalid");
        assertEquals("UNAVAILABLE",ApprovedSopStore.inspect(active,"色斑",user,date).get("status"));
    }
}
