package com.upc.computer.assistant.v2.runtime;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundedAnalysisTest {
    @Test void removesUnissuedReferencesAndDoesNotTurnLiteratureIntoBusinessFindings() {
        var result=GroundedAnalysis.parse("QUALITY","""
          {"summary":"需核验","findings":[{"text":"检验记录待核实","evidenceRefs":["q1"]},
          {"text":"论文证明整批合格","evidenceRefs":["r:p:c"]},
          {"text":"伪造已确认根因","evidenceRefs":["q1","invented"]}],
          "hypotheses":[{"text":"搬运可能影响外观","evidenceRefs":["q1","r:p:c"],"verification":"核对搬运记录"}],
          "actions":[],"missing":["缺历史图像"]}
          """,Set.of("q1"),Set.of("r:p:c"));
        assertEquals(1,((List<?>)result.get("findings")).size());
        assertEquals(1,((List<?>)result.get("hypotheses")).size());
        assertFalse(result.toString().contains("伪造已确认根因"));
        assertEquals("PARTIAL_INVALID_REFERENCES",result.get("status"));
    }
    @Test void rawProseInvalidJsonAndDuplicateKeysCannotMasqueradeAsReferencedAnalysis() {
        for(String input:List.of("该批次已经放行", "{\"summary\":\"a\",\"summary\":\"b\"}")) {
            var result=GroundedAnalysis.parse("QUALITY",input,Set.of("q1"),Set.of());
            assertEquals("INVALID_FORMAT",result.get("status"));
            assertEquals(List.of(),result.get("findings"));
            assertFalse(result.toString().contains("该批次已经放行"));
        }
    }
}
