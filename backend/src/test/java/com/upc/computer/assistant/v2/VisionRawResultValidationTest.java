package com.upc.computer.assistant.v2;

import com.fasterxml.jackson.databind.*;
import com.upc.computer.config.YoloProperties;
import com.upc.computer.service.YoloVisionClient;
import com.upc.computer.assistant.v2.vision.VisionEvidenceArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class VisionRawResultValidationTest {
    @TempDir Path dir;
    @SuppressWarnings("unchecked") private Map<String,Object> normalize(String json) throws Exception {
        var method=YoloVisionClient.class.getDeclaredMethod("normalizeResult",JsonNode.class);method.setAccessible(true);
        return (Map<String,Object>)method.invoke(new YoloVisionClient(new YoloProperties()),new ObjectMapper().readTree(json));
    }
    @Test void emptyLoadingAndTypeInvalidResponsesCannotBecomeRealEvidence() throws Exception {
        var archive=new VisionEvidenceArchive(dir,Clock.systemUTC());
        for(String raw:List.of("{}","{\"status\":\"loading\"}","{\"defect\":false,\"count\":\"0\",\"maxConfidence\":0,\"detections\":[]}")) {
            var response=normalize(raw);
            assertThat(response).containsEntry("defect",false).containsEntry("count",0); // Legacy response compatibility.
            assertThat(archive.save(7,new byte[]{1,2,3},response)).containsEntry("status","NOT_SAVED").containsEntry("reason","INVALID_MODEL_RESULT");
        }
    }
    @Test void actualCoreFieldsPermitArchiveButDefaultModelLabelIsNotAWeightsVersion() throws Exception {
        var response=normalize("{\"defect\":false,\"count\":0,\"maxConfidence\":0,\"detections\":[]}");
        assertThat(response).containsEntry("modelProvenance","FALLBACK_DEFAULT").containsEntry("weightsVersion","WEIGHTS_VERSION_UNAVAILABLE");
        assertThat(new VisionEvidenceArchive(dir,Clock.systemUTC()).save(7,new byte[]{1,2,3},response)).containsEntry("status","SAVED");
    }
    @Test void detectionCoercionCannotInventMissingRawFields() throws Exception {
        for(String detection:List.of("{\"box\":[1,2,3,4]}","{\"classId\":0,\"className\":\"Scratched\",\"confidence\":\"0.9\",\"box\":[1,2,3,4]}","{\"classId\":0,\"className\":\"Scratched\",\"confidence\":0.9,\"box\":[\"1\",2,3,4]}")) {
            var response=normalize("{\"defect\":true,\"count\":1,\"maxConfidence\":0.9,\"detections\":["+detection+"]}");
            assertThat(new VisionEvidenceArchive(dir,Clock.systemUTC()).save(7,new byte[]{1,2,3},response)).containsEntry("status","NOT_SAVED").containsEntry("reason","INVALID_MODEL_RESULT");
        }
    }
}
