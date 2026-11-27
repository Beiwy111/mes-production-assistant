package com.upc.computer.assistant.v2.operations;

import com.upc.computer.ai.action.AgentActionPlanService;
import com.upc.computer.assistant.v2.orders.OrderSessionGuard;
import com.upc.computer.common.BusinessException;
import com.upc.computer.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/assistant-v2/operations")
public class ProductionOperationController {
    private final OrderSessionGuard sessions;
    private final ProductionOperationService operations;
    private final AgentActionPlanService plans;

    public ProductionOperationController(OrderSessionGuard sessions, ProductionOperationService operations, AgentActionPlanService plans) {
        this.sessions = sessions;
        this.operations = operations;
        this.plans = plans;
    }

    @ModelAttribute
    public void preventCaching(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @PostMapping("/preview")
    public Result<?> preview(@RequestBody ProductionOperationService.Turn turn,
                             @RequestHeader(value="Authorization", required=false) String authorization) {
        return Result.success(operations.preview(turn, sessions.requireActive(authorization)));
    }

    @PostMapping("/{id}/decision")
    public Result<?> decide(@PathVariable String id, @RequestBody Map<String,String> body,
                            @RequestHeader(value="Authorization", required=false) String authorization) {
        var session = sessions.requireActive(authorization);
        if (!body.keySet().equals(Set.of("decision")) ||
                !("APPROVE".equals(body.get("decision")) || "SKIP".equals(body.get("decision"))))
            throw new BusinessException(400, "请核对方案后确认或取消");
        return Result.success(plans.confirmProduction(id, body.get("decision"), session));
    }
}
