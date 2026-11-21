package com.upc.computer.assistant.v2.api;

import com.upc.computer.assistant.v2.application.AssistantFacade;
import com.upc.computer.common.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/assistant/v2/runs")
@ConditionalOnProperty(prefix = "assistant.v2", name = "enabled", havingValue = "true")
public class AssistantRunController {
    private final AssistantFacade assistant;
    public AssistantRunController(AssistantFacade assistant) { this.assistant = assistant; }
    @GetMapping("/{runId}")
    public Result<Map<String, Object>> status(@PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Result.success(assistant.status(runId, authorization));
    }
    @PostMapping("/{runId}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Result.success(assistant.cancel(runId, authorization));
    }
}
